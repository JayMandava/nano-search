#!/usr/bin/env python3
"""Install pinned public Android archives without accepting licenses or using sudo."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

BASE = 'https://dl.google.com/android/repository/'
LICENSE_SHA256 = '1f8729233617b193fd619213792ae16a41b95d2bbbf525dfe66998252ba68b16'
PACKAGES = [
    ('commandlinetools-linux-13114758_latest.zip', '5fdcc763663eefb86a5b8879697aa6088b041e70', 'cmdline-tools/19.0'),
    ('build-tools_r35_linux.zip', '2cfaa0bbb2336e9ec18ed3ecea84fa2e2af607bc', 'build-tools/35.0.0'),
    ('android-ndk-r27-linux.zip', '5e5cd517bdb98d7e0faf2c494a3041291e71bdcc', 'ndk/27.0.12077973'),
    ('platform-36_r02.zip', '2c1a80dd4d9f7d0e6dd336ec603d9b5c55a6f576', 'platforms/android-36'),
    ('cmake-3.22.1-linux.zip', 'fd0a48b4a758310df8c7aa51f59840ed48fe7ed8', 'cmake/3.22.1'),
]


def require(value, message):
    if not value:
        raise ValueError(message)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


def install(signer_only):
    require(os.environ.get('GITHUB_ACTIONS') == 'true', 'This installer is for GitHub-hosted jobs only')
    require(os.environ.get('RUNNER_ENVIRONMENT') == 'github-hosted', 'Fresh GitHub-hosted runner required')
    require(os.environ.get('RUNNER_OS') == 'Linux' and os.environ.get('RUNNER_ARCH') == 'X64', 'Linux x64 required')
    existing = Path(os.environ['ANDROID_HOME']) / 'licenses/android-sdk-license'
    require(existing.is_file() and existing.stat().st_size > 0,
            'Runner has no existing Android SDK license acceptance; stop for owner setup')
    root = Path(os.environ['RUNNER_TEMP']) / 'nano-android-sdk'
    require(not root.exists(), 'Use a fresh job tool directory; do not overwrite existing state')
    opener = urllib.request.build_opener(NoRedirect())
    with opener.open(BASE + 'repository2-1.xml', timeout=30) as response:
        tree = ET.fromstring(response.read())
    license_text = next(x.text for x in tree if x.tag.endswith('license') and x.get('id') == 'android-sdk-license')
    require(hashlib.sha256(license_text.encode()).hexdigest() == LICENSE_SHA256,
            'Android package license text changed; owner review required')
    root.mkdir(mode=0o700)
    for filename, digest, target in PACKAGES[:3] if signer_only else PACKAGES:
        matches = [p for p in tree if p.tag.endswith('remotePackage') and
                   any(a.findtext('url') == filename and a.findtext('checksum') == digest
                       for a in p.findall('./archives/archive/complete'))]
        require(matches and all(p.find('uses-license').get('ref') == 'android-sdk-license' for p in matches),
                'Pinned package metadata/license changed: ' + filename)
        with tempfile.TemporaryDirectory(dir=os.environ['RUNNER_TEMP']) as tmp:
            stage = Path(tmp)
            archive = stage / 'archive.zip'
            with opener.open(BASE + filename, timeout=60) as response, archive.open('wb') as output:
                shutil.copyfileobj(response, output)
            require(hashlib.sha1(archive.read_bytes()).hexdigest() == digest, 'Official archive checksum mismatch')
            unpacked = stage / 'unpacked'
            subprocess.run(['unzip', '-q', str(archive), '-d', str(unpacked)], check=True)
            # SDK archives use either one enclosing directory or a flat layout.
            children = list(unpacked.iterdir())
            source = children[0] if len(children) == 1 and children[0].is_dir() else unpacked
            destination = root / target
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(source), destination)
    with open(os.environ['GITHUB_ENV'], 'a') as out:
        out.write(f'ANDROID_HOME={root}\nANDROID_SDK_ROOT={root}\n')
    if not signer_only:
        with open(os.environ['GITHUB_PATH'], 'a') as out:
            out.write(str(root / 'cmake/3.22.1/bin') + '\n')
        local = Path('app/local.properties')
        require(not local.exists(), 'Unexpected local.properties; refusing overwrite')
        local.write_text(f'sdk.dir={root}\ncmake.dir={root}/cmake/3.22.1\n')
    # No sdkmanager --licenses, acceptance receipts, signing keys or credential files.


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--signer-only', action='store_true')
    install(parser.parse_args().signer_only)
