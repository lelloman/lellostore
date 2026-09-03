import contextlib
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock


SCRIPT_PATH = Path(__file__).parents[1] / "publish-to-lellostore.py"
SPEC = importlib.util.spec_from_file_location("lellostore_publisher", SCRIPT_PATH)
publisher = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(publisher)


class FakeHttpResponse:
    def __init__(self, payload, status=201, reason="Created"):
        self.payload = json.dumps(payload).encode()
        self.status = status
        self.reason = reason

    def read(self):
        return self.payload


class FakeConnection:
    def __init__(self, response):
        self.response = response
        self.request = None
        self.headers = []
        self.chunks = []
        self.closed = False

    def putrequest(self, method, path):
        self.request = (method, path)

    def putheader(self, name, value):
        self.headers.append((name, value))

    def endheaders(self):
        pass

    def send(self, chunk):
        self.chunks.append(chunk)

    def getresponse(self):
        return self.response

    def close(self):
        self.closed = True


class PublisherConfigTest(unittest.TestCase):
    def test_resolves_required_configuration_from_environment(self):
        config = publisher.PublisherConfig.resolve(
            store_url=None,
            issuer=None,
            client_id=None,
            environ={
                "LELLOSTORE_URL": "https://store.example.com/",
                "LELLOSTORE_OIDC_ISSUER": "https://auth.example.com/",
                "LELLOSTORE_CLIENT_ID": "publisher-client",
            },
        )

        self.assertEqual(config.store_url, "https://store.example.com")
        self.assertEqual(config.issuer, "https://auth.example.com")
        self.assertEqual(config.client_id, "publisher-client")

    def test_command_line_configuration_overrides_environment(self):
        config = publisher.PublisherConfig.resolve(
            store_url="https://cli-store.example.com",
            issuer="https://cli-auth.example.com",
            client_id="cli-client",
            environ={
                "LELLOSTORE_URL": "https://env-store.example.com",
                "LELLOSTORE_OIDC_ISSUER": "https://env-auth.example.com",
                "LELLOSTORE_CLIENT_ID": "env-client",
            },
        )

        self.assertEqual(config.store_url, "https://cli-store.example.com")
        self.assertEqual(config.issuer, "https://cli-auth.example.com")
        self.assertEqual(config.client_id, "cli-client")

    def test_rejects_missing_configuration(self):
        with self.assertRaisesRegex(publisher.PublisherError, "LELLOSTORE_URL"):
            publisher.PublisherConfig.resolve(None, None, None, {})

    def test_requires_https_unless_explicitly_allowed(self):
        with self.assertRaisesRegex(publisher.PublisherError, "HTTPS"):
            publisher.PublisherConfig.resolve(
                "http://store.example.com",
                "https://auth.example.com",
                "client",
                {},
            )

        config = publisher.PublisherConfig.resolve(
            "http://127.0.0.1:8080",
            "http://127.0.0.1:9999",
            "client",
            {},
            allow_insecure_http=True,
        )
        self.assertEqual(config.store_url, "http://127.0.0.1:8080")

    def test_token_cache_is_scoped_to_issuer_and_client(self):
        first = publisher.PublisherConfig(
            "https://store.example.com",
            "https://auth-one.example.com",
            "client-one",
        )
        second = publisher.PublisherConfig(
            "https://store.example.com",
            "https://auth-two.example.com",
            "client-two",
        )

        with tempfile.TemporaryDirectory() as directory:
            cache_dir = Path(directory)
            self.assertNotEqual(
                publisher.token_file_for(first, cache_dir),
                publisher.token_file_for(second, cache_dir),
            )


class UploadTest(unittest.TestCase):
    def setUp(self):
        self.config = publisher.PublisherConfig(
            "https://store.example.com",
            "https://auth.example.com",
            "publisher-client",
        )

    def test_streams_artifact_and_reads_snake_case_response(self):
        response = {
            "package_name": "com.example.publisher",
            "name": "Publisher App",
            "version": {
                "version_name": "2.0",
                "version_code": 20,
            },
        }
        connection = FakeConnection(FakeHttpResponse(response))

        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "publisher.apk"
            artifact.write_bytes(b"test apk")
            output = io.StringIO()
            with mock.patch.object(
                publisher,
                "_open_connection",
                return_value=connection,
            ), mock.patch.object(
                Path,
                "read_bytes",
                side_effect=AssertionError("uploads must stream from disk"),
            ), contextlib.redirect_stdout(output):
                result = publisher.upload_artifact(
                    artifact,
                    "access-token",
                    self.config,
                )

        self.assertEqual(result, response)
        self.assertEqual(connection.request, ("POST", "/api/admin/apps"))
        self.assertIn(("Authorization", "Bearer access-token"), connection.headers)
        self.assertIn(b"test apk", b"".join(connection.chunks))
        self.assertTrue(connection.closed)
        self.assertIn("Package: com.example.publisher", output.getvalue())
        self.assertIn("Version: 2.0 (20)", output.getvalue())

    def test_json_mode_prints_machine_readable_result(self):
        response = {
            "package_name": "com.example.publisher",
            "name": "Publisher App",
            "version": {"version_name": "2.0", "version_code": 20},
        }
        connection = FakeConnection(FakeHttpResponse(response))

        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "publisher.aab"
            artifact.write_bytes(b"test aab")
            stdout = io.StringIO()
            with mock.patch.object(
                publisher,
                "_open_connection",
                return_value=connection,
            ), contextlib.redirect_stdout(stdout):
                publisher.upload_artifact(
                    artifact,
                    "access-token",
                    self.config,
                    json_output=True,
                )

        self.assertEqual(json.loads(stdout.getvalue()), response)

    def test_beta_upload_includes_release_channel_field(self):
        response = {
            "package_name": "com.example.publisher",
            "name": "Publisher App",
            "version": {"version_name": "2.0-beta", "version_code": 20},
        }
        connection = FakeConnection(FakeHttpResponse(response))

        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "publisher.apk"
            artifact.write_bytes(b"test apk")
            with mock.patch.object(
                publisher,
                "_open_connection",
                return_value=connection,
            ), contextlib.redirect_stdout(io.StringIO()):
                publisher.upload_artifact(
                    artifact,
                    "access-token",
                    self.config,
                    is_beta=True,
                )

        body = b"".join(connection.chunks)
        self.assertIn(b'name="is_beta"', body)
        self.assertIn(b"true", body)


class CommandLineTest(unittest.TestCase):
    ENVIRONMENT = {
        "LELLOSTORE_URL": "https://store.example.com",
        "LELLOSTORE_OIDC_ISSUER": "https://auth.example.com",
        "LELLOSTORE_CLIENT_ID": "publisher-client",
    }

    def test_dry_run_validates_without_authenticating_or_uploading(self):
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "publisher.apk"
            artifact.write_bytes(b"test apk")
            stdout = io.StringIO()
            with mock.patch.object(publisher, "device_flow_auth") as auth, mock.patch.object(
                publisher,
                "upload_artifact",
            ) as upload, contextlib.redirect_stdout(stdout):
                status = publisher.main(
                    ["upload", str(artifact), "--dry-run", "--json"],
                    environ=self.ENVIRONMENT,
                )

        self.assertEqual(status, 0)
        auth.assert_not_called()
        upload.assert_not_called()
        result = json.loads(stdout.getvalue())
        self.assertEqual(result["status"], "valid")
        self.assertEqual(result["artifact_type"], "apk")

    def test_legacy_artifact_invocation_is_treated_as_upload(self):
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "publisher.apk"
            artifact.write_bytes(b"test apk")
            with mock.patch.object(
                publisher,
                "device_flow_auth",
                return_value="access-token",
            ) as auth, mock.patch.object(
                publisher,
                "upload_artifact",
                return_value={},
            ) as upload:
                status = publisher.main(
                    [str(artifact), "--yes"],
                    environ=self.ENVIRONMENT,
                )

        self.assertEqual(status, 0)
        auth.assert_called_once()
        upload.assert_called_once()

    def test_beta_flag_is_forwarded_to_upload(self):
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "publisher.apk"
            artifact.write_bytes(b"test apk")
            with mock.patch.object(
                publisher,
                "device_flow_auth",
                return_value="access-token",
            ), mock.patch.object(
                publisher,
                "upload_artifact",
                return_value={},
            ) as upload:
                status = publisher.main(
                    ["upload", str(artifact), "--beta", "--yes"],
                    environ=self.ENVIRONMENT,
                )

        self.assertEqual(status, 0)
        self.assertTrue(upload.call_args.kwargs["is_beta"])

    def test_script_has_no_personal_endpoint_defaults(self):
        source = SCRIPT_PATH.read_text()
        self.assertNotIn('STORE_URL = "https://store.lelloman.com"', source)
        self.assertNotIn('OIDC_ISSUER = "https://auth.lelloman.com"', source)
        self.assertNotIn('CLIENT_ID = "22cd4a2d-a771-41e3-b76e-3f83ff8e9bbf"', source)
        self.assertNotIn("script_path.write_text", source)


if __name__ == "__main__":
    unittest.main()
