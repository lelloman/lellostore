import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('paravoid_device', Path(__file__).with_name('paravoid-device.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
APP = 'com.lelloman.paravoidcompat.complete.paravoid'


class DeviceSafetyTest(unittest.TestCase):
    def validate(self, serial='emulator-5590', package=APP, changes=None):
        responses = {
            ('shell', 'getprop', 'ro.kernel.qemu'): '1',
            ('emu', 'avd', 'name'): 'LelloStoreParavoid30\nOK',
            ('shell', 'getprop', 'ro.build.version.sdk'): '30',
            ('shell', 'pm', 'path', APP): '',
            ('reverse', '--list'): '',
        }
        responses.update(changes or {})
        module.validate_target(serial, package, lambda *args, **_: responses[args])

    def test_accepts_fresh_dedicated_emulator(self):
        self.validate()

    def test_rejects_physical_devices_and_other_packages(self):
        for changes in ({'serial': 'phone-123'}, {'package': 'com.example.realapp'}):
            with self.subTest(changes=changes), self.assertRaises(AssertionError):
                self.validate(**changes)

    def test_rejects_existing_apps_ports_and_unowned_emulators(self):
        for key, value in [
            (('shell', 'getprop', 'ro.kernel.qemu'), '0'),
            (('emu', 'avd', 'name'), 'PersonalEmulator\nOK'),
            (('shell', 'getprop', 'ro.build.version.sdk'), '29'),
            (('shell', 'pm', 'path', APP), 'package:/data/app/base.apk'),
            (('reverse', '--list'), 'host tcp:18765 tcp:4444'),
        ]:
            with self.subTest(key=key), self.assertRaises(AssertionError):
                self.validate(changes={key: value})
