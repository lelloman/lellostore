import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "setup-store.py"
spec = importlib.util.spec_from_file_location("setup_store", SCRIPT)
setup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(setup)


class SetupStoreTest(unittest.TestCase):
    def test_unattended_setup_is_portable_and_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "store.env"
            command = ["python3", str(SCRIPT), "--non-interactive", "--output", str(output),
                       "--name", 'Team "Example"', "--server-url", "https://store.example.org/",
                       "--issuer", "https://id.example.org/realm", "--push"]
            completed = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(completed.returncode, 0, completed.stderr)
            original = output.read_bytes()
            values = dict(line.split("=", 1) for line in output.read_text().splitlines() if not line.startswith("#"))
            values = {key: json.loads(value) for key, value in values.items()}
            self.assertEqual(values["STORE_NAME"], 'Team "Example"')
            self.assertEqual(values["PUSH_PUBLIC_BASE_URL"], "https://store.example.org")
            self.assertEqual(values["OIDC_ANDROID_CLIENT_ID"], "store-android")
            self.assertNotIn(b"lelloman.com", original)
            if os.name == "posix":
                self.assertEqual(output.stat().st_mode & 0o777, 0o600)
            self.assertNotEqual(subprocess.run(command, capture_output=True).returncode, 0)
            self.assertEqual(output.read_bytes(), original)

    def test_rejects_ambiguous_or_insecure_origins(self):
        for url in ["http://store.example.org", "https://user:pass@store.example.org", "https://store.example.org/path", "https://store.example.org?x=1", "https://store.example.org/#fragment", "https://store.example.org:8443"]:
            with self.subTest(url=url), self.assertRaises(ValueError):
                setup.https_url(url, origin=True)

    def test_does_not_create_partial_configuration(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "store.env"
            result = subprocess.run(["python3", str(SCRIPT), "--non-interactive", "--output", str(output),
                "--name", "bad\nNAME=oops", "--server-url", "https://store.example.org", "--issuer", "https://id.example.org"], capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(output.exists())
