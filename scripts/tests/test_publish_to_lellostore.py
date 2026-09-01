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


class FakeResponse:
    def __init__(self, payload):
        self.payload = json.dumps(payload).encode()

    def __enter__(self):
        return self

    def __exit__(self, _type, _value, _traceback):
        return False

    def read(self):
        return self.payload


class UploadApkTest(unittest.TestCase):
    def test_prints_snake_case_upload_response_fields(self):
        response = {
            "package_name": "com.example.publisher",
            "name": "Publisher App",
            "version": {
                "version_name": "2.0",
                "version_code": 20,
            },
        }

        with tempfile.TemporaryDirectory() as directory:
            apk_path = Path(directory) / "publisher.apk"
            apk_path.write_bytes(b"test apk")
            output = io.StringIO()
            with mock.patch.object(
                publisher.urllib.request,
                "urlopen",
                return_value=FakeResponse(response),
            ), contextlib.redirect_stdout(output):
                result = publisher.upload_apk(apk_path, "access-token")

        self.assertEqual(result, response)
        self.assertIn("Package: com.example.publisher", output.getvalue())
        self.assertIn("Version: 2.0 (20)", output.getvalue())


if __name__ == "__main__":
    unittest.main()
