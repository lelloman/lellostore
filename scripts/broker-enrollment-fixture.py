#!/usr/bin/env python3
"""Opt-in, isolated HTTPS enrollment qualification on the PIN-2468 test emulator.
Requires a built target/debug/lello-auth, adb, openssl, and an isolated emulator.
Creates synthetic accounts only in a fresh temporary database; never uses production.
"""
import argparse
import base64
import hashlib
import json
import http.client
import http.server
import os
from pathlib import Path
import socket
import sqlite3
import ssl
import subprocess
import tempfile
import threading
import time
import uuid

# Public synthetic fixture password: fixture-password-2468. Never a production credential.
PASSWORD_HASH = '$argon2id$v=19$m=19456,t=2,p=1$JMZT7Dx0rTUkhDwez4Iu5Q$Pds4q7/I3VkSwI6Yj5wVKJiIn7tAvEPrzkKhBOimpks'
STORE_ROOT = Path(__file__).resolve().parents[1]
ROOT = STORE_ROOT / "vendor/lello-auth"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, help='Explicit isolated emulator serial')
    parser.add_argument('--allow-other-devices', action='store_true',
                        help='Permit other attached devices; every adb and Gradle step is still pinned to --serial through -s and ANDROID_SERIAL')
    args = parser.parse_args()
    if not args.serial.startswith('emulator-'):
        parser.error('This fixture only runs on an explicitly selected emulator')
    for port in (18880, 19443):
        with socket.socket() as probe:
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            probe.bind(('127.0.0.1', port))
    devices = subprocess.check_output(['adb', 'devices'], text=True).splitlines()[1:]
    connected = [line.split()[0] for line in devices if len(line.split()) == 2 and line.split()[1] == 'device']
    if args.serial not in connected:
        parser.error('The selected emulator is not connected')
    if connected != [args.serial] and not args.allow_other_devices:
        parser.error('Disconnect other devices before running this emulator-only fixture, or pass --allow-other-devices')
    adb = ['adb', '-s', args.serial]
    subprocess.run(adb + ['get-state'], check=True)
    server = None
    proxy = None
    reverse = False
    with tempfile.TemporaryDirectory(prefix='lelloauth-enrollment-') as directory:
        work = Path(directory)
        cert = subprocess.check_output(['keytool', '-exportcert', '-alias', 'androiddebugkey', '-keystore', str(Path.home() / '.android/debug.keystore'), '-storepass', 'android'])
        fingerprint = hashlib.sha256(cert).hexdigest()
        config = work / 'config.toml'
        config.write_text(f'''[server]
host = "127.0.0.1"
port = 18880
external_url = "https://localhost:19443"
[database]
path = "{work}/auth.db"
[keys]
private_key_path = "{work}/private.pem"
public_key_path = "{work}/public.pem"
[authenticator]
enabled = true
android_package = "com.lelloman.store.debug"
android_certificates = ["{fingerprint}"]
[rate_limit]
enabled = false
''')
        with (work / 'server.log').open('w') as log:
            try:
                subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes',
                                '-keyout', str(work / 'tls-key.pem'), '-out', str(work / 'tls.pem'),
                                '-days', '2', '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost'],
                               check=True, stdout=log, stderr=log)
                env = os.environ.copy()
                # Configuration is entirely fixture-owned; inherited production overrides are removed.
                env = {k: v for k, v in env.items() if not k.startswith('LELLOAUTH_')}
                env['LELLOAUTH_DATA_PROTECTION_KEY'] = base64.b64encode(os.urandom(32)).decode()
                server = subprocess.Popen([str(ROOT / 'target/debug/lello-auth'), '-c', str(config), 'serve'],
                                          env=env, stdout=log, stderr=log)
                for _ in range(120):
                    if server.poll() is not None:
                        raise RuntimeError('Fixture server did not start')
                    try:
                        with socket.create_connection(('127.0.0.1', 18880), timeout=.2):
                            break
                    except OSError:
                        time.sleep(.25)
                else:
                    raise RuntimeError('Fixture server startup timed out')
                with sqlite3.connect(work / 'auth.db') as db:
                    callback = 'lelloauth-fixture:/callback'
                    db.execute('INSERT INTO apps(id,display_name,redirect_uris,created_at) VALUES (?,?,?,?)', ('android-native-fixture', 'Synthetic music app', json.dumps([callback]), int(time.time())))
                    db.execute('INSERT INTO authenticator_client_policies(app_id,enabled,privileged,callbacks_json) VALUES (?,1,0,?)', ('android-native-fixture', json.dumps([{'redirect_uri': callback, 'package': 'com.lelloman.store.debug', 'certificate_sha256': fingerprint}])))
                    for index, username in enumerate(('android-fixture', 'android-fixture-two')):
                        now = int(time.time())
                        user_id = str(uuid.uuid4())
                        db.execute('INSERT INTO users(id,username,password_hash,created_at,updated_at,is_admin) VALUES (?,?,?,?,?,?)',
                                   (user_id, username, PASSWORD_HASH, now, now, int(index == 0)))
                        db.execute('INSERT INTO user_app_access(user_id,app_id,granted_at) VALUES (?,?,?)', (user_id, "android-native-fixture", now))

                class Proxy(http.server.BaseHTTPRequestHandler):
                    def log_message(self, *unused):
                        pass
                    def do_GET(self):
                        self.forward()
                    def do_POST(self):
                        self.forward()
                    def forward(self):
                        conn = http.client.HTTPConnection('127.0.0.1', 18880, timeout=20)
                        try:
                            size = int(self.headers.get('Content-Length', '0'))
                            if size > 128 * 1024:
                                self.send_error(413)
                                return
                            conn.request(self.command, self.path, body=self.rfile.read(size), headers=dict(self.headers))
                            response = conn.getresponse()
                            if response.status >= 400:
                                print(f'Fixture HTTP {self.command} {self.path.split(chr(63))[0]} -> {response.status}', flush=True)
                            body = response.read()
                            self.send_response(response.status)
                            for key, value in response.getheaders():
                                if key.lower() not in ('content-length', 'transfer-encoding', 'connection'):
                                    self.send_header(key, value)
                            self.send_header('Content-Length', str(len(body)))
                            self.end_headers()
                            self.wfile.write(body)
                        finally:
                            conn.close()

                proxy = http.server.ThreadingHTTPServer(('127.0.0.1', 19443), Proxy)
                context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
                context.load_cert_chain(work / 'tls.pem', work / 'tls-key.pem')
                proxy.socket = context.wrap_socket(proxy.socket, server_side=True)
                threading.Thread(target=proxy.serve_forever, daemon=True).start()
                subprocess.run(adb + ['reverse', 'tcp:19443', 'tcp:19443'], check=True)
                reverse = True
                test_env = os.environ.copy()
                test_env['ANDROID_SERIAL'] = args.serial
                subprocess.run([str(STORE_ROOT / 'android/gradlew'), '-p', str(STORE_ROOT / 'android'),
                                ':app:connectedDebugAndroidTest', '-PbrokerEnabled=true', '-PbrokerDevIssuer=https://localhost:19443',
                                f'-PbrokerFixtureCaPath={work}/tls.pem',
                                '-Pandroid.testInstrumentationRunnerArguments.class=com.lelloman.store.auth.ServerEnrollmentDeviceTest',
                                '-Pandroid.testInstrumentationRunnerArguments.localEnrollment=true'], env=test_env, check=True)
                with sqlite3.connect(work / 'auth.db') as db:
                    assert db.execute('SELECT COUNT(*), COUNT(DISTINCT user_id) FROM authenticator_devices').fetchone() == (2, 2)
                    assert db.execute("SELECT COUNT(*) FROM authenticator_transactions WHERE status='approved'").fetchone() == (2,)
                print('Two accounts enrolled without prompts; one tap-only and one prompt-gated native OAuth approval completed.')
            finally:
                if reverse:
                    subprocess.run(adb + ['reverse', '--remove', 'tcp:19443'], check=False)
                if proxy:
                    proxy.shutdown()
                    proxy.server_close()
                if server:
                    server.terminate()
                    try:
                        server.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        server.kill()
                        server.wait()


if __name__ == '__main__':
    main()
