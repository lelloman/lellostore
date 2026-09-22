#!/usr/bin/env python3
"""Store wrapper for pinned Paravoid personalization; verifier is mandatory."""
import argparse
import importlib.util
from pathlib import Path
import subprocess

source = Path(__file__).parent / 'vendor/paravoid/apk_personalize.py'
spec = importlib.util.spec_from_file_location('paravoid_personalize', source)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path)
    parser.add_argument('output', type=Path)
    for name in ('grant', 'trust', 'contract', 'audience', 'channel', 'apksigner', 'verifier'):
        parser.add_argument('--' + name, required=True)
    args = parser.parse_args()

    def verify(mode, path):
        result = subprocess.run([args.verifier, args.trust, args.contract, args.audience,
                                 args.channel, mode, str(path)], capture_output=True, timeout=60)
        if result.returncode:
            raise ValueError('Grant verification failed')

    try:
        module.personalize(args.input, args.output, args.grant, args.apksigner, verify)
    except (ValueError, OSError, subprocess.SubprocessError):
        raise SystemExit('Personalization failed; no acquisition was published')


if __name__ == '__main__':
    main()
