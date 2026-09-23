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


def validate_target(serial, package, adb):
    """Read-only checks must finish before port mapping or installation."""
    assert re.fullmatch(r'emulator-\d+', serial), 'Only disposable emulators are supported'
    assert package == 'com.lelloman.paravoidcompat.complete.paravoid'
    assert adb('shell', 'getprop', 'ro.kernel.qemu').strip() == '1'
    assert adb('emu', 'avd', 'name').splitlines()[0].startswith('LelloStoreParavoid'), 'Use a dedicated Store test AVD'
    assert int(adb('shell', 'getprop', 'ro.build.version.sdk').strip()) in (30, 36)
    assert not adb('shell', 'pm', 'path', package, check=False).strip(), 'Existing installation refused'
    assert 'tcp:18765' not in adb('reverse', '--list'), 'Existing port mapping refused'


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

    validate_target(serial, package, adb)
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

    def expect(text):
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            state = ui()
            if text in state:
                return state
            time.sleep(.3)
        raise AssertionError('Missing UI state: ' + text + '\n' + state)

    def tap(label):
        node = next(n for n in ET.fromstring(ui()).iter('node') if n.attrib.get('text', '').lower() == label.lower())
        a, b, c, d = map(int, re.findall(r'\d+', node.attrib['bounds']))
        adb('shell', 'input', 'tap', (a + c) // 2, (b + d) // 2)

    def launch():
        adb('shell', 'am', 'start', '-W', '-n', package + '/com.lelloman.paravoidandroid.runtime.LauncherActivity')

    def controls():
        adb('shell', 'am', 'start', '-W', '-n', package + '/com.lelloman.paravoidandroid.runtime.UpdatesLauncher')
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

    fixture = Path(settings['fixture'])
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Proxy)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(fixture / 'build/keys/server.pem', fixture / 'build/keys/server-key.pem')
    server.socket = context.wrap_socket(server.socket, server_side=True)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    reversed_port = False
    try:
        adb('reverse', 'tcp:18765', 'tcp:' + str(server.server_port))
        reversed_port = True
        adb('install', settings['source'])
        launch()
        expect('Update access unavailable')
        assert not observed, 'Missing grant must not contact delivery endpoints'
        assert 'not debuggable' in adb('shell', 'run-as', package, 'id', check=False).lower()
        adb('install', '-r', settings['apk'])
        launch()
        expect('Pending: ' + settings['release'])
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
        if reversed_port:
            adb('reverse', '--remove', 'tcp:18765', check=False)
        # Retain fixture installation/evidence on this disposable emulator.


if __name__ == '__main__':
    main(sys.argv[1])
