#!/usr/bin/env python3
"""Read or increment the shared Android version; never installs or changes signing keys."""
import argparse
import re
from pathlib import Path

VERSION_FILE = Path(__file__).resolve().parents[1] / 'app-version.properties'


def read_version(path=VERSION_FILE):
    values = dict(line.strip().split('=', 1) for line in path.read_text().splitlines()
                  if line.strip() and not line.lstrip().startswith('#'))
    code = int(values['versionCode'])
    name = values['versionName']
    if not 0 < code <= 2_100_000_000 or not re.fullmatch(r'\d+\.\d+(?:\.\d+)?', name):
        raise ValueError('Revisa versionCode y versionName en app-version.properties')
    return code, name


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--next', metavar='VERSION', help='Ejemplo: --next 1.2; aumenta el código en 1')
    args = parser.parse_args()
    code, name = read_version()
    if args.next:
        if not re.fullmatch(r'\d+\.\d+(?:\.\d+)?', args.next) or code >= 2_100_000_000:
            parser.error('Usa una versión como 1.2 o 1.2.1 dentro del límite Android')
        code, name = code + 1, args.next
        VERSION_FILE.write_text(f'versionCode={code}\nversionName={name}\n')
    print(f'ControlPrestamos {name} · código {code}')
