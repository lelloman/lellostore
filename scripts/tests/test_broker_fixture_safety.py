import subprocess
import sys
import unittest
from pathlib import Path


class BrokerFixtureSafetyTest(unittest.TestCase):
    def test_physical_serial_is_refused_before_any_device_command(self):
        fixture = Path(__file__).resolve().parents[1] / "broker-enrollment-fixture.py"
        result = subprocess.run(
            [sys.executable, str(fixture), "--serial", "physical-phone"],
            capture_output=True, text=True, check=False,
        )
        self.assertEqual(2, result.returncode)
        self.assertIn("only runs on an explicitly selected emulator", result.stderr)
