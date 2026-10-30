#!/usr/bin/env python3
"""Stage one pinned, privately provisioned app sound. Never downloads or prints audio."""
import argparse
import base64
import binascii
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import struct
import sys
import tempfile
import zipfile

MANIFEST = Path(__file__).with_name('monza-release.json')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def specification():
    return json.loads(MANIFEST.read_text(encoding='utf-8'))


def ogg_crc(data):
    """Ogg's non-reflected CRC-32 (not ZIP's CRC)."""
    crc = 0
    for value in data:
        crc ^= value << 24
        for _ in range(8):
            crc = ((crc << 1) ^ (0x04C11DB7 if crc & 0x80000000 else 0)) & 0xFFFFFFFF
    return crc


def packet_samples(packet):
    require(len(packet) >= 2, 'Invalid Opus packet')
    toc = packet[0]
    config = toc >> 3
    if config >= 16:
        frame = 120 << (config & 3)
    elif config >= 12:
        frame = 480 << (config & 1)
    else:
        frame = (480, 960, 1920, 2880)[config & 3]
    count = 1 if toc & 3 == 0 else (2 if toc & 3 in (1, 2) else packet[1] & 63)
    require(1 <= count <= 48 and frame * count <= 5760, 'Invalid Opus frame duration')
    return frame * count


def inspect_container(data):
    """Validate the fixed mono/48k/4s CBR profile, not perceptual quality or a decoder."""
    offset = 0
    sequence = 0
    serial = None
    packets = []
    last_granule = 0
    total_samples = 0
    ended = False
    while offset < len(data):
        require(not ended and len(data) - offset >= 27, 'Truncated or chained Ogg stream')
        header = data[offset:offset + 27]
        require(header[:4] == b'OggS' and header[4] == 0, 'Invalid Ogg header')
        flags = header[5]
        require(flags & ~6 == 0, 'Continued or unknown Ogg page flags')
        granule, page_serial, page_sequence, checksum = struct.unpack_from('<QIII', header, 6)
        require(page_sequence == sequence, 'Nonsequential Ogg pages')
        if serial is None:
            serial = page_serial
        require(page_serial == serial, 'Multiple Ogg streams')
        require(bool(flags & 2) == (sequence == 0), 'Invalid Ogg beginning')
        count = header[26]
        require(count > 0 and offset + 27 + count <= len(data), 'Truncated Ogg lacing')
        lengths = data[offset + 27:offset + 27 + count]
        require(all(length < 255 for length in lengths), 'Unexpected continued packet')
        end = offset + 27 + count + sum(lengths)
        require(end <= len(data), 'Truncated Ogg body')
        page = bytearray(data[offset:end])
        page[22:26] = bytes(4)
        require(ogg_crc(page) == checksum, 'Ogg checksum mismatch')
        body = offset + 27 + count
        for length in lengths:
            require(length > 0, 'Empty Opus packet')
            packet = data[body:body + length]
            packets.append(packet)
            if len(packets) > 2:
                samples = packet_samples(packet)
                require(samples == 960 and len(packet) == 160, 'Expected 64kbps CBR / 20ms Opus')
                require(packet[0] & 4 == 0, 'Unexpected stereo Opus packet')
                total_samples += samples
            body += length
        if sequence == 0:
            require(count == 1 and granule == 0, 'OpusHead must be alone')
        elif len(packets) <= 2:
            require(granule == 0, 'Invalid OpusTags granule')
        else:
            require(last_granule <= granule <= total_samples, 'Invalid Ogg granule')
            if not flags & 4:
                require(granule == total_samples, 'Unexpected Opus timeline offset')
        last_granule = granule
        ended = bool(flags & 4)
        sequence += 1
        offset = end
    require(ended and len(packets) > 2, 'Missing Ogg end/audio')
    head = packets[0]
    require(len(head) == 19 and head[:8] == b'OpusHead', 'Invalid OpusHead')
    require(head[8] == 1 and head[9] == 1 and head[18] == 0, 'Expected mono mapping-family-zero Opus')
    pre_skip = struct.unpack_from('<H', head, 10)[0]
    require(struct.unpack_from('<I', head, 12)[0] == 48000, 'Expected 48kHz input')
    require(struct.unpack_from('<h', head, 16)[0] == 0, 'Unexpected Opus output gain')
    require(packets[1].startswith(b'OpusTags') and 16 <= len(packets[1]) <= 4096, 'Invalid OpusTags')
    require(last_granule - pre_skip == 192000, 'Expected exactly four decoded seconds')
    require(0 <= total_samples - last_granule < 960, 'Invalid Opus end trimming')
    return {'channels': 1, 'sample_rate': 48000, 'samples': 192000, 'bitrate': 64000}


def validate(data):
    expected = specification()
    require(len(data) == expected['bytes'], 'Release audio size mismatch')
    require(hashlib.sha256(data).hexdigest() == expected['sha256'], 'Release audio SHA-256 mismatch')
    return inspect_container(data)


def read_asset(path):
    path = Path(path)
    require(path.is_absolute() and not path.is_symlink(), 'Expected absolute regular audio input')
    info = path.stat()
    require(stat.S_ISREG(info.st_mode) and info.st_size == specification()['bytes'],
            'Release audio input missing or wrong size')
    data = path.read_bytes()
    validate(data)
    return data


def write_private(path, data):
    path = Path(path)
    require(path.is_absolute() and not path.is_symlink(), 'Unsafe audio output')
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    require(not path.parent.is_symlink(), 'Unsafe audio output directory')
    with tempfile.NamedTemporaryFile(dir=path.parent, prefix='.nano-audio-', delete=False) as out:
        temporary = Path(out.name)
        try:
            os.fchmod(out.fileno(), 0o600)
            out.write(data)
            out.flush()
            os.fsync(out.fileno())
            os.replace(temporary, path)
        finally:
            temporary.unlink(missing_ok=True)


def prepare(output):
    expected = specification()
    value = os.environ.get(expected['secret_name'], '').strip()
    require(value, expected['secret_name'] + ' is missing; release audio is mandatory')
    require(len(value) == expected['base64_characters'] and value.isascii(),
            'Release audio secret has invalid length/encoding')
    try:
        data = base64.b64decode(value, validate=True)
    except (ValueError, binascii.Error):
        raise ValueError('Release audio secret is not canonical Base64') from None
    require(base64.b64encode(data).decode('ascii') == value, 'Noncanonical audio secret')
    validate(data)
    write_private(output, data)


def stage(source, directory):
    directory = Path(directory)
    require(directory.is_absolute() and not directory.is_symlink(), 'Unsafe generated resource directory')
    raw = directory / 'raw'
    require(not raw.is_symlink(), 'Unsafe generated raw directory')
    if directory.exists():
        require({p.name for p in directory.iterdir()} <= {'raw'}, 'Unexpected generated resources')
    if raw.exists():
        require({p.name for p in raw.iterdir()} <= {'nano_supercar_rev.ogg'}, 'Unexpected generated audio')
    output = raw / specification()['file_name']
    require(not output.is_symlink(), 'Unsafe generated audio output')
    try:
        data = read_asset(source)
        write_private(output, data)
    except (ValueError, OSError):
        output.unlink(missing_ok=True)  # A failed attempt cannot leave a stale usable asset.
        raise


def verify_packaged(archive, dump):
    """Resolve the resource table: R8 may rename res/raw/... to e.g. res/VR.ogg."""
    blocks = re.split(r'(?m)^\s*resource ', dump)
    matches = [b for b in blocks if re.match(r'0x[0-9a-fA-F]+\s+(?:[\w.]+:)?raw/nano_supercar_rev(?:\s|$)', b)]
    require(len(matches) == 1, 'Expected one packaged race-car resource')
    paths = re.findall(r'\(file\)\s+(res/[^\s]+)', matches[0])
    require(len(paths) == 1 and paths[0].endswith('.ogg') and '..' not in paths[0].split('/'),
            'Expected one Ogg resource value; synthetic fallback forbidden')
    entry = archive.getinfo(paths[0])
    require(entry.compress_type == zipfile.ZIP_STORED, 'Audio must be uncompressed for resource FD playback')
    validate(archive.read(entry))
    require(not any(name.endswith('.wav') for name in archive.namelist()), 'Unexpected WAV in release APK')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    prepare_parser = sub.add_parser('prepare')
    prepare_parser.add_argument('--output', required=True)
    stage_parser = sub.add_parser('stage')
    stage_parser.add_argument('--input', required=True)
    stage_parser.add_argument('--res-dir', required=True)
    args = parser.parse_args()
    try:
        if args.command == 'prepare':
            prepare(args.output)
        else:
            stage(args.input, args.res_dir)
    except (ValueError, OSError) as error:
        # No payload, environment dump, decoder output or exception traceback.
        print('Release audio validation failed: ' + str(error), file=sys.stderr)
        return 1
    print('Verified pinned release audio; no audio data logged.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
