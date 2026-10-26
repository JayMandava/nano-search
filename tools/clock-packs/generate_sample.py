#!/usr/bin/env python3
"""Generate an original, deterministic, data-only analog-v1 test face (standard library only)."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import struct
import zipfile
import zlib

LICENSE = '''Orbit Blue — original Nano example face

To the extent possible under law, the authors of this original example waive
all copyright and related or neighboring rights to its manifest and generated
PNG artwork under CC0 1.0 Universal (CC0-1.0).
https://creativecommons.org/publicdomain/zero/1.0/

The artwork is a small programmatically drawn blue texture, not a scan, logo,
font, or reproduction of a commercial watch. No third-party assets are used.
This example is provided without warranty. It is a developer test fixture,
not a claim that a public download service exists.
'''.encode('utf-8')


def chunk(kind, payload):
    return struct.pack('>I', len(payload)) + kind + payload + struct.pack('>I', zlib.crc32(kind + payload))


def texture():
    # Integer-only subtle square texture. Native renderer clips it to the dial.
    raw = bytearray()
    for y in range(64):
        raw.append(0)  # PNG filter None
        for x in range(64):
            glow = max(0, 28 - ((2*x-63)**2 + (2*y-63)**2) // 128)
            grain = ((x * 17 + y * 29) % 7) - 3
            raw.extend((15 + glow//3 + grain, 31 + glow//2 + grain, 49 + glow + grain))
    # One explicit uncompressed zlib block: byte-for-byte stable across zlib versions.
    stream = b'\x78\x01\x01' + struct.pack('<HH', len(raw), len(raw) ^ 0xffff) + raw + struct.pack('>I', zlib.adler32(raw))
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 64, 64, 8, 2, 0, 0, 0)) + chunk(b'IDAT', stream) + chunk(b'IEND', b'')


def generate():
    png = texture()
    manifest = {
        'format': 'nano.clock-face-pack', 'schemaVersion': 1,
        'packId': 'orbit-blue', 'packVersion': 1, 'name': 'Orbit Blue',
        'author': 'Nano examples', 'description': 'An original blue analog dial for developer testing.',
        'license': 'CC0-1.0', 'minAppVersionCode': 14,
        'requires': ['analog-v1', 'png-v1'], 'template': 'analog-v1',
        'dial': {'backgroundColor': '#FF101F31', 'textureAsset': 'texture',
                 'ticks': 'minute', 'tickColor': '#FFADC4D9', 'numerals': 'quarters',
                 'numeralColor': '#FFE8F1F8', 'font': 'system-sans'},
        'hands': {
            'hour': {'shape': 'bar', 'color': '#FFE8F1F8', 'length': 0.25, 'width': 0.025, 'tail': 0.025},
            'minute': {'shape': 'needle', 'color': '#FFE8F1F8', 'length': 0.39, 'width': 0.014, 'tail': 0.035},
            'second': {'shape': 'needle', 'color': '#FFFFB64D', 'length': 0.42, 'width': 0.004, 'tail': 0.055},
        },
        'dateWindow': {'position': 'right', 'textColor': '#FFE8F1F8', 'backgroundColor': '#FF182B3E'},
        'assets': [{'key': 'texture', 'path': 'assets/texture.png', 'sha256': hashlib.sha256(png).hexdigest()}],
    }
    payloads = [('manifest.json', (json.dumps(manifest, ensure_ascii=True, separators=(',', ':')) + '\n').encode()),
                ('LICENSE.txt', LICENSE), ('assets/texture.png', png)]
    out = io.BytesIO()
    with zipfile.ZipFile(out, 'w', compression=zipfile.ZIP_STORED, allowZip64=False) as archive:
        for name, data in payloads:
            info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            info.compress_type = zipfile.ZIP_STORED
            archive.writestr(info, data)
    return out.getvalue()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', nargs='?', type=Path,
                        default=Path(__file__).resolve().parents[2] / 'app/app/src/androidTest/assets/clock-packs/orbit-blue.zip')
    args = parser.parse_args()
    data = generate()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(data)
    print(json.dumps({'packId': 'orbit-blue', 'packVersion': 1, 'bytes': len(data),
                      'sha256': hashlib.sha256(data).hexdigest(), 'file': str(args.output)}, indent=2))


if __name__ == '__main__':
    main()
