"""Public synthetic container fixtures only. No recording or encoded stock payload."""
import base64
import contextlib
import hashlib
import importlib.util
import io
import os
from pathlib import Path
import stat
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('nano_audio_test', ROOT / 'tools/audio/release_audio.py')
audio = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audio)


def page(sequence, packets, granule=0, flags=0, serial=1234):
    lengths = bytes(map(len, packets))
    header = b'OggS' + bytes([0, flags]) + struct.pack('<QIII', granule, serial, sequence, 0) + bytes([len(packets)])
    result = bytearray(header + lengths + b''.join(packets))
    struct.pack_into('<I', result, 22, audio.ogg_crc(result))
    return bytes(result)


def container(channels=1, rate=48000, samples=192000, bitrate=64000):
    # These opaque packet bodies exercise container framing, NOT codec decoding.
    # They are generated test data, never the licensed recording or a release asset.
    head = b'OpusHead' + struct.pack('<BBHIhB', 1, channels, 312, rate, 0, 0)
    vendor = b'Nano public synthetic test'
    tags = b'OpusTags' + struct.pack('<I', len(vendor)) + vendor + struct.pack('<I', 0)
    packet = bytes([31 << 3]) + bytes(bitrate // 400 - 1)
    return page(0, [head], flags=2) + page(1, [tags]) + page(2, [packet] * 201, samples + 312, 4)


class ContainerTests(unittest.TestCase):
    def test_crc_known_vector(self):
        self.assertEqual(audio.ogg_crc(b'123456789'), 0x89A1897F)

    def test_generated_container_profile(self):
        self.assertEqual(audio.inspect_container(container()),
                         {'channels': 1, 'sample_rate': 48000, 'samples': 192000, 'bitrate': 64000})

    def test_checksum_truncation_and_chained_stream_rejected(self):
        value = bytearray(container()); value[-1] ^= 1
        for value in (bytes(value), container()[:-1], container() + container()):
            with self.subTest(length=len(value)), self.assertRaises(ValueError):
                audio.inspect_container(value)

    def test_wrong_channel_rate_duration_and_bitrate_rejected(self):
        for kwargs in ({'channels': 2}, {'rate': 24000}, {'samples': 191999}, {'bitrate': 32000}):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                audio.inspect_container(container(**kwargs))

    def test_missing_eos_rejected(self):
        head = b'OpusHead' + struct.pack('<BBHIhB', 1, 1, 312, 48000, 0, 0)
        with self.assertRaisesRegex(ValueError, 'end/audio'):
            audio.inspect_container(page(0, [head], flags=2))

    def test_wrong_serial_and_sequence_rejected(self):
        for sequence, serial in ((2, 1234), (1, 5678)):
            bad = page(0, [b'OpusHead'], flags=2) + page(sequence, [b'OpusTags'], serial=serial)
            with self.assertRaises(ValueError):
                audio.inspect_container(bad)


class ProvisioningTests(unittest.TestCase):
    def test_exact_public_metadata_pin(self):
        value = audio.specification()
        self.assertEqual(value['sha256'], '4fffd803c5a8a7f6fa40a5ea3dca493b75b191c4a2d0ffa6516b37cc07c77c6e')
        self.assertEqual((value['bytes'], value['base64_characters']), (32632, 43512))
        self.assertEqual(value['secret_name'], 'NANO_RM_AUDIO_OGG_BASE64')
        self.assertEqual(value['environment'], 'production-release')
        self.assertEqual(value['source']['sha256'], '1ee75789384cef25b108165a722aea5dd19b054d01ac96176cb597bc2a945833')

    def test_synthetic_fixture_cannot_pass_production_pin(self):
        for value in (container(), bytes(32632)):
            with self.assertRaisesRegex(ValueError, 'size|SHA-256'):
                audio.validate(value)

    def test_missing_secret_never_creates_output(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, {}, clear=True):
            out = Path(tmp) / 'private.ogg'
            with self.assertRaisesRegex(ValueError, 'missing'):
                audio.prepare(out)
            self.assertFalse(out.exists())

    def test_wrong_but_well_formed_secret_never_creates_output(self):
        # Base64 generated in memory from zeros; no payload literals or output.
        value = base64.b64encode(bytes(32632)).decode('ascii')
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, {'NANO_RM_AUDIO_OGG_BASE64': value}):
            out = Path(tmp) / 'private.ogg'
            with self.assertRaisesRegex(ValueError, 'SHA-256'):
                audio.prepare(out)
            self.assertFalse(out.exists())

    def test_errors_do_not_log_secret_value(self):
        sentinel = 'DO_NOT_LOG_THIS_'
        value = sentinel + 'a' * (43512 - len(sentinel))
        stderr = io.StringIO()
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, {'NANO_RM_AUDIO_OGG_BASE64': value}), \
                patch.object(sys, 'argv', ['release_audio.py', 'prepare', '--output', str(Path(tmp) / 'x.ogg')]), \
                contextlib.redirect_stderr(stderr):
            self.assertEqual(audio.main(), 1)
        self.assertNotIn(sentinel, stderr.getvalue())
        self.assertNotIn(value, stderr.getvalue())

    def test_wrong_input_removes_stale_generated_asset(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); source = root / 'wrong.ogg'; source.write_bytes(b'public fixture')
            generated = root / 'generated'; raw = generated / 'raw'; raw.mkdir(parents=True)
            stale = raw / 'nano_supercar_rev.ogg'; stale.write_bytes(b'stale fixture')
            with self.assertRaises(ValueError):
                audio.stage(source, generated)
            self.assertFalse(stale.exists())

    def test_unrelated_resource_directory_is_not_a_generic_overlay(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); (root / 'values').mkdir()
            with patch.object(audio, 'read_asset') as read, self.assertRaisesRegex(ValueError, 'Unexpected'):
                audio.stage(Path(tmp) / 'source.ogg', root)
            read.assert_not_called()

    def test_symlink_input_and_output_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); link = root / 'link.ogg'; link.symlink_to('/not-read')
            with self.assertRaisesRegex(ValueError, 'regular'):
                audio.read_asset(link)
            with self.assertRaisesRegex(ValueError, 'Unsafe'):
                audio.write_private(link, b'public fixture')

    def test_cli_has_no_hash_override(self):
        with patch.object(sys, 'argv', ['release_audio.py', 'stage', '--input', '/x', '--res-dir', '/y', '--sha256', '0' * 64]), contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                audio.main()


class PackagedResourceTests(unittest.TestCase):
    dump = '    resource 0x7f090000 raw/nano_supercar_rev\n      () (file) res/short.ogg\n'

    def archive(self, name='res/short.ogg', compression=zipfile.ZIP_STORED, extra=False):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w') as out:
            out.writestr(name, b'public synthetic bytes', compress_type=compression)
            if extra:
                out.writestr('res/fallback.wav', b'public synthetic fallback')
        stream.seek(0)
        return zipfile.ZipFile(stream)

    def test_resolves_shortened_resource_path_and_validates_exact_bytes(self):
        with self.archive() as archive, patch.object(audio, 'validate') as validate:
            audio.verify_packaged(archive, self.dump)
            validate.assert_called_once_with(b'public synthetic bytes')

    def test_compressed_resource_rejected_before_data_validation(self):
        with self.archive(compression=zipfile.ZIP_DEFLATED) as archive, patch.object(audio, 'validate') as validate:
            with self.assertRaisesRegex(ValueError, 'uncompressed'):
                audio.verify_packaged(archive, self.dump)
            validate.assert_not_called()

    def test_fallback_or_ambiguous_resource_rejected(self):
        for dump in (self.dump.replace('.ogg', '.wav'), self.dump + self.dump, ''):
            with self.archive() as archive, self.assertRaises(ValueError):
                audio.verify_packaged(archive, dump)

    def test_extra_wav_is_not_shipped_with_the_ogg(self):
        with self.archive(extra=True) as archive, patch.object(audio, 'validate'), self.assertRaisesRegex(ValueError, 'WAV'):
            audio.verify_packaged(archive, self.dump)


if __name__ == '__main__':
    unittest.main()
