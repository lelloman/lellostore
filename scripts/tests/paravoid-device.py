#!/usr/bin/env python3
"""Installed HTTPS acceptance for the isolated Store integration-test backend.

Input is a private temporary control file produced by the Rust test. Only fresh
LelloStoreParavoid emulators are accepted. No phone or existing app is modified.
"""
import base64
import hashlib
import io
import http.client
import http.server
import json
from pathlib import Path
import re
import ssl
import subprocess
import sys
import threading
import time
import urllib.request
import urllib.parse
import xml.etree.ElementTree as ET
import zipfile


def validate_target(serial, package, adb, store_ui=False):
    """Read-only checks must finish before port mapping or installation."""
    assert re.fullmatch(r'emulator-\d+', serial), 'Only disposable emulators are supported'
    assert package == 'com.lelloman.paravoidcompat.complete.paravoid'
    assert adb('shell', 'getprop', 'ro.kernel.qemu').strip() == '1'
    assert adb('emu', 'avd', 'name').splitlines()[0].startswith('LelloStoreParavoid'), 'Use a dedicated Store test AVD'
    assert int(adb('shell', 'getprop', 'ro.build.version.sdk').strip()) in (30, 36)
    assert not adb('shell', 'pm', 'list', 'packages', '-3').strip(), 'Emulator contains unrelated user apps'
    assert not adb('shell', 'pm', 'path', package, check=False).strip(), 'Existing installation refused'
    assert 'tcp:18765' not in adb('reverse', '--list'), 'Existing port mapping refused'
    if store_ui:
        assert 'tcp:18766' not in adb('reverse', '--list'), 'Existing Store port mapping refused'
        for app in ('com.lelloman.store.debug', 'com.lelloman.store.debug.test'):
            assert not adb('shell', 'pm', 'path', app, check=False).strip(), 'Existing Store installation refused'


def main(control):
    if not __debug__:
        raise RuntimeError("Acceptance requires Python assertions; do not enable optimization")
    settings = json.loads(Path(control).read_text())
    serial, package = settings['serial'], settings['package']
    assert re.fullmatch(r'emulator-\d+', serial), 'Only disposable emulators are supported'
    assert package == 'com.lelloman.paravoidcompat.complete.paravoid'

    def adb(*parts, check=True):
        result = subprocess.run(['adb', '-s', serial, *map(str, parts)], capture_output=True, text=True, timeout=60)
        assert not check or result.returncode == 0, result.stdout + result.stderr
        return result.stdout + result.stderr

    store_ui = settings.get('store_ui', False)
    validate_target(serial, package, adb, store_ui)
    address = urllib.parse.urlparse(settings['server'])
    assert address.scheme == 'http' and address.hostname == '127.0.0.1', 'Only a local Store test server is allowed'

    def request(path, role, data=None, content_type='application/json'):
        req = urllib.request.Request(settings['server'].rstrip('/') + path,
            data=None if data is None else data if isinstance(data, bytes) else json.dumps(data).encode(),
            headers={'Authorization': settings[role], 'Content-Type': content_type})
        with urllib.request.urlopen(req, timeout=180) as response:
            return response.read()

    def ui():
        adb('shell', 'uiautomator', 'dump', '/sdcard/lellostore-paravoid.xml')
        return adb('shell', 'cat', '/sdcard/lellostore-paravoid.xml')

    def expect(text, process=None, log=None):
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            if process is not None and process.poll() is not None:
                raise AssertionError('Store instrumentation exited: ' + log.read_text())
            state = ui()
            if any(value in state for value in ((text,) if isinstance(text, str) else text)):
                return state
            time.sleep(.3)
        raise AssertionError('Missing UI state: ' + str(text) + '\n' + state)

    def tap(label, allowed_packages=None):
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            node = next((n for n in ET.fromstring(ui()).iter('node')
                         if n.attrib.get('text', '').lower() == label.lower()
                         and n.attrib.get('package') in (allowed_packages or (package, 'com.lelloman.store.debug'))), None)
            if node is not None:
                assert node.attrib.get('package') in (allowed_packages or (package, 'com.lelloman.store.debug')), 'Refusing to interact with an unrelated app'
                a, b, c, d = map(int, re.findall(r'\d+', node.attrib['bounds']))
                adb('shell', 'input', 'tap', (a + c) // 2, (b + d) // 2)
                return
            time.sleep(.3)
        raise AssertionError('Missing actionable label: ' + label)

    def launch():
        adb('shell', 'am', 'start', '-W', '-n', package + '/com.lelloman.paravoidandroid.runtime.LauncherActivity')

    def controls():
        adb('shell', 'am', 'start', '-W', '-f', '0x14000000', '-n', package + '/com.lelloman.paravoidandroid.runtime.UpdatesLauncher')
        expect('A local app generation is available.')

    observed = []  # Paths/status only; no bearer credentials or token headers.

    class Proxy(http.server.BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_GET(self):
            assert self.path.startswith('/v1/apps/'), 'Unexpected fixture route'
            connection = http.client.HTTPConnection(urllib.parse.urlparse(settings['server']).netloc, timeout=60)
            try:
                headers = {k: v for k, v in self.headers.items() if k.lower() not in ('host', 'connection')}
                connection.request('GET', '/api/paravoid' + self.path, headers=headers)
                response = connection.getresponse()
                observed.append((self.path, response.status))
                self.send_response(response.status)
                for key, value in response.getheaders():
                    if key.lower() not in ('connection', 'transfer-encoding', 'server', 'date'):
                        self.send_header(key, value)
                self.end_headers()
                while chunk := response.read(65536):
                    self.wfile.write(chunk)
            finally:
                connection.close()

    if store_ui:
        outputs = Path(__file__).resolve().parents[2] / 'android/app/build/outputs/apk'
        store_apk = outputs / 'debug/app-debug.apk'
        test_apk = outputs / 'androidTest/debug/app-debug-androidTest.apk'
        assert store_apk.is_file() and test_apk.is_file(), 'Build Store debug and AndroidTest APKs first'

    def store_install(action):
        print('Starting Store UI ' + action, serial, flush=True)
        previous = adb('shell', 'pm', 'path', package, check=False).strip()
        adb('shell', 'run-as', 'com.lelloman.store.debug', 'rm', '-f', 'cache/store-ui-finished')
        args = ['adb', '-s', serial, 'shell', 'am', 'instrument', '-w', '-r',
                '-e', 'class', 'com.lelloman.store.e2e.ParavoidStoreDeviceTest',
                '-e', 'storeDevice', 'true', '-e', 'storeServer', 'http://127.0.0.1:18766',
                '-e', 'storeToken', settings['user'].removeprefix('Bearer '),
                'com.lelloman.store.debug.test/com.lelloman.store.HiltTestRunner']
        # Capture instrumentation output without ever printing its credential arguments.
        log = Path(control).parent / ('instrumentation-' + action + '.log')
        with log.open('w+') as output:
            process = subprocess.Popen(args, stdout=output, stderr=subprocess.STDOUT)
            try:
                name = json.loads(request('/api/apps/' + package, 'user'))['name']
                expect(name, process, log)
                tap(name, ('com.lelloman.store.debug',))
                label = 'Repair update access' if action == 'repair' else 'Install'
                expect(label, process, log)
                tap(label)
                if action == 'repair':
                    expect('Reinstall', process, log)
                    tap('Reinstall')
                deadline = time.monotonic() + 120
                confirmed = False
                resumed = False
                while process.poll() is None and time.monotonic() < deadline:
                    state = ui()
                    assert 'Installation did not complete' not in state and 'App not installed' not in state, 'Store download or installation failed'
                    nodes = list(ET.fromstring(state).iter('node'))
                    # Do not send a credential-bearing test APK to the optional
                    # Play Protect cloud scan. Use its per-install local option;
                    # leave device-wide verification settings unchanged.
                    if name in state and 'App scan recommended' in state:
                        play_nodes = [n for n in nodes if n.attrib.get('package') == 'com.android.vending']
                        local = next((n for n in play_nodes if n.attrib.get('text', '').endswith('Install without scanning')), None)
                        if local is not None:
                            a, b, c, d = map(int, re.findall(r'\d+', local.attrib['bounds']))
                            adb('shell', 'input', 'tap', (a+c)//2, d-20)
                        elif any(n.attrib.get('text') == 'More details' for n in play_nodes):
                            tap('More details', ('com.android.vending',))
                    for node in nodes:
                        attrs = node.attrib
                        if (attrs.get('package') in ('com.android.packageinstaller', 'com.google.android.packageinstaller', 'com.android.permissioncontroller')
                                and attrs.get('text', '').lower() in ('install', 'update') and attrs.get('enabled') == 'true'):
                            a, b, c, d = map(int, re.findall(r'\d+', attrs['bounds']))
                            adb('shell', 'input', 'tap', (a+c)//2, (b+d)//2)
                            confirmed = True
                            print('Confirmed Android installer ' + action, serial, flush=True)
                    installed = adb('shell', 'pm', 'path', package, check=False).strip()
                    if confirmed and installed and installed != previous and not resumed:
                        expect(('App installed', 'App updated'), process, log)
                        # Leave the old completion task available: repair must
                        # open a fresh installer even though its APK URI is reused.
                        adb('shell', 'am', 'start', '-W', '-n', 'com.lelloman.store.debug/com.lelloman.store.MainActivity')
                        resumed = True
                        expect('Open', process, log)
                        expect('Manage app updates', process, log)
                        tap('Manage app updates')
                        expect('Automatically check for updates', process, log)
                        adb('shell', 'run-as', 'com.lelloman.store.debug', 'touch', 'cache/store-ui-finished')
                    time.sleep(.3)
                assert process.poll() is not None, 'Store instrumentation timed out'
                output.seek(0)
                result = output.read()
                assert process.returncode == 0 and 'OK (1 test)' in result, result
                assert confirmed and resumed, 'Android installation confirmation was not exercised'
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait(timeout=10)
        print('PASS Store Android UI ' + action + ', installed state and exported update controls', serial, flush=True)

    fixture = Path(settings['fixture'])
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Proxy)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(fixture / 'build/keys/server.pem', fixture / 'build/keys/server-key.pem')
    server.socket = context.wrap_socket(server.socket, server_side=True)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    reversed_port = False
    store_reversed = False
    try:
        adb('reverse', 'tcp:18765', 'tcp:' + str(server.server_port))
        reversed_port = True
        if store_ui:
            adb('reverse', 'tcp:18766', 'tcp:' + str(address.port))
            store_reversed = True
            adb('install', store_apk)
            adb('install', test_apk)
            adb('shell', 'appops', 'set', 'com.lelloman.store.debug', 'REQUEST_INSTALL_PACKAGES', 'allow')
            adb('shell', 'pm', 'grant', 'com.lelloman.store.debug', 'android.permission.POST_NOTIFICATIONS', check=False)
        print('Starting canonical missing-grant check', serial, flush=True)
        adb('install', settings['source'])
        launch()
        expect('Update access unavailable')
        assert not observed, 'Missing grant must not contact delivery endpoints'
        assert 'not debuggable' in adb('shell', 'run-as', package, 'id', check=False).lower()
        if store_ui:
            # Only remove the empty canonical fixture installed above by this run.
            adb('uninstall', package)
            store_install('install')
            overview = json.loads(request('/api/admin/apps/' + package + '/distribution', 'admin'))
            assert len(overview['grants']) == 1
            settings['acquisition'] = overview['grants'][0]['acquisition_id']
        else:
            adb('install', '-r', settings['apk'])
        launch()
        # Opening controls from Store may finish the first download before the
        # launcher starts; that first launch can activate the staged generation.
        initial = expect(('Pending: ' + settings['release'], 'generation=A;asset=payload-asset;java=payload-java-resource'))
        if 'Pending: ' + settings['release'] in initial:
            tap('Restart app…')
            tap('Stop and restart')
        expect('generation=A;asset=payload-asset;java=payload-java-resource')
        assert any('/head?' in path and status == 200 for path, status in observed)
        assert any(path.endswith('/payload.vpk') and status == 200 for path, status in observed)
        print('PASS Store-issued signed APK: HTTPS bootstrap and payload activation', serial, flush=True)

        # Publish a distinct, higher identity using the real fixture release key.
        # Components stay unchanged; this tests update delivery, not a behavioral A/B change.
        installed_path = adb('shell', 'pm', 'path', package).strip().removeprefix('package:')
        installed_hash = adb('shell', 'sha256sum', installed_path).split()[0]
        with zipfile.ZipFile(fixture / 'build/outputs/paravoid/paravoidAndroidRelease/payload.vpk') as original:
            components = {entry.filename: original.read(entry) for entry in original.infolist()}
        envelope = json.loads(components['release.json'])
        release = json.loads(base64.b64decode(envelope['body']))
        release['payloadVersion'] += 1
        release['releaseId'] = 'store-p2'
        encoded = json.dumps(release, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()
        signature = subprocess.run(['openssl', 'dgst', '-sha256', '-sign', str(fixture / 'build/keys/release.der'), '-keyform', 'DER'],
            input=b'paravoid/v1/release\n' + encoded, capture_output=True, check=True, timeout=30).stdout
        envelope.update(body=base64.b64encode(encoded).decode(), signature=base64.b64encode(signature).decode())
        components['release.json'] = json.dumps(envelope, sort_keys=True, separators=(',', ':')).encode()
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_STORED) as target:
            for name, content in sorted(components.items()):
                entry = zipfile.ZipInfo(name)
                entry.create_system = 3
                entry.external_attr = 0o100644 << 16
                target.writestr(entry, content)
        boundary = 'lellostore-device-vpk'
        form = ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="update.vpk"\r\n'
                'Content-Type: application/vnd.paravoid.vpk\r\n\r\n').encode() + archive.getvalue() + ('\r\n--' + boundary + '--\r\n').encode()
        job = json.loads(request('/api/admin/apps/' + package + '/contracts/' + release['shellContractId'] + '/vpks',
                                 'admin', form, 'multipart/form-data; boundary=' + boundary))
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            status = json.loads(request('/api/admin/uploads/' + job['id'], 'admin'))
            if status['status'] in ('ready', 'failed'):
                break
            time.sleep(.2)
        assert status['status'] == 'ready', 'Update VPK validation failed'
        overview = json.loads(request('/api/admin/apps/' + package + '/distribution', 'admin'))
        update = next(r for r in overview['releases'] if r['release_id'] == 'store-p2')
        request('/api/admin/apps/' + package + '/vpks/' + update['id'] + '/publish', 'admin',
                {'expected_revision': overview['publication_revision']})
        controls()
        tap('Check now')
        expect('Pending: store-p2')
        tap('Restart app…')
        tap('Stop and restart')
        expect('generation=A;asset=payload-asset;java=payload-java-resource')
        controls()
        expect('Current: store-p2')
        assert adb('shell', 'pm', 'path', package).strip().removeprefix('package:') == installed_path
        assert adb('shell', 'sha256sum', installed_path).split()[0] == installed_hash
        print('PASS Store-published second payload activates with unchanged shell APK', serial, flush=True)

        controls()
        overview = json.loads(request('/api/admin/apps/' + package + '/distribution', 'admin'))
        grant = next(g for g in overview['grants'] if g['acquisition_id'] == settings['acquisition'])
        request('/api/admin/apps/' + package + '/grants/' + grant['id'] + '/revoke', 'admin',
                {'expected_revision': overview['publication_revision']})
        tap('Check now')
        expect('Update access unavailable')
        assert any(status == 403 for _, status in observed)
        adb('shell', 'am', 'force-stop', package)
        launch()
        expect('generation=A;asset=payload-asset;java=payload-java-resource')
        if store_ui:
            store_install('repair')
        else:
            acquired = json.loads(request('/api/apps/' + package + '/acquisitions', 'user',
                {'version_code': settings['version'], 'purpose': 'repair', 'idempotency_key': 'device-repair'}))
            repaired = request(acquired['apk_url'], 'user')
            assert len(repaired) == acquired['size'] and hashlib.sha256(repaired).hexdigest() == acquired['sha256']
            replacement = Path(control).parent / 'repair.apk'
            replacement.write_bytes(repaired)
            adb('install', '-r', replacement)
        launch()
        expect('generation=A;asset=payload-asset;java=payload-java-resource')
        controls()
        tap('Check now')
        expect('Update: READY')
        print('PASS Store revocation and same-version access repair preserve active payload', serial, flush=True)
        server.shutdown()
        server.server_close()
        adb('shell', 'am', 'force-stop', package)
        launch()
        expect('generation=A;asset=payload-asset;java=payload-java-resource')
        print('PASS cold offline payload execution with Store delivery stopped', serial, flush=True)
    finally:
        server.shutdown()
        server.server_close()
        if store_reversed:
            adb('reverse', '--remove', 'tcp:18766', check=False)
        if reversed_port:
            adb('reverse', '--remove', 'tcp:18765', check=False)
        # Retain fixture installation/evidence on this disposable emulator.


if __name__ == '__main__':
    main(sys.argv[1])
