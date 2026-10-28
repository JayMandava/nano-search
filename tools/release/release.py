#!/usr/bin/env python3
"""Fail-closed release checks. No signing credentials are consumed by this module."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import struct
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile

REPO = 'JayMandava/nano-search'
ENVIRONMENT = 'production-release'
CERT = '1f08d7d9aa073e5d7c4f2c9fd8caca761fc42f108c05e7a5ac88ea9dbc5332ce'
API = 'https://api.github.com'
# Reviewed public asset provenance; never derive this policy from the incoming APK.
PUBLIC_ASSETS = {
    'Barlow-SemiBold.ttf': '86577cb32f8abe3673db53ca0f4221e6856751a4f6730c867e00f720f8bb1fc5',
    'OFL-Barlow.txt': '186d750eb496a4c17a76385f82be6aea2ac1cf2de074a811d63786cf374ea73f',
    'OFL-SourceSans3.txt': '09746787287a289323b0ec3cff4d1a4a801331b82b7207c1e186f5d26619a392',
    'SourceSans3.ttf': '042fe2cc0b933e328410d7acbd0aa6a1873dca5aef81875f4bc214b08825c7b9',
    'brand_icons.tsv': 'c3e2fea2c714875c9e5155181fae078fba547a06edb97eb91f6f33c1554901fd',
    'clip_tokenizer.json': '72ed5c96db5729294468543e4bc75fce14ca63f58e37300290189ba1c1e52b85',
    'parser.gbnf': '1519fa0e9bacda943d86eb61592e0cf19cb1863177ca3a6eb194d238f6f1e389',
    'parser_selftest.txt': '3e342c628ac111bba756d880989cf336478648648758fb06e2cf375dee69bb3c',
    'parser_system.txt': 'fda216bf3b47dcd0ca1df8ab95b65e68b4aeba566280aee3e49b9d643e029ec6',
    'places.tsv': 'c8ce6c8403bea412d1044ccecb0cc3e36a8879f35c289b2c1dd7f6a592913232',
}
GENERATED_ASSETS = {'dexopt/baseline.prof', 'dexopt/baseline.profm'}
NATIVE_LIBRARIES = {'libandroidx.graphics.path.so', 'libnanollm.so', 'libnanollm_gpu.so',
                    'libonnxruntime.so', 'libonnxruntime4j_jni.so'}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        # API redirects are unexpected; never forward an Authorization header to another origin.
        return None


URL_OPENER = urllib.request.build_opener(NoRedirect)



def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def version_tuple(version):
    require(re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', version), 'Use a stable X.Y.Z version without leading zeroes')
    return tuple(map(int, version.split('.')))


def source_version(text):
    names = re.findall(r'^\s*versionName\s*=\s*"([^"]+)"\s*$', text, re.M)
    codes = re.findall(r'^\s*versionCode\s*=\s*([0-9]+)\s*$', text, re.M)
    require(len(names) == len(codes) == 1, 'Ambiguous application version metadata')
    version_tuple(names[0])
    require(int(codes[0]) > 0, 'Invalid versionCode')
    return names[0], int(codes[0])


def api(path, method='GET', data=None, absent_ok=False, binary=False):
    # Only GitHub's API origin receives the token. Do not accept arbitrary upload URLs.
    require(path.startswith('/') and not path.startswith('//'), 'Invalid API path')
    headers = {'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'nano-search-release'}
    token = os.environ.get('GH_TOKEN')
    require(bool(token), 'GitHub token missing')
    headers['Authorization'] = 'Bearer ' + token
    if binary:
        body = data
        headers['Content-Type'] = 'application/octet-stream'
        origin = 'https://uploads.github.com'
    else:
        body = None if data is None else json.dumps(data).encode()
        headers['Content-Type'] = 'application/json'
        origin = API
    request = urllib.request.Request(origin + path, data=body, headers=headers, method=method)
    try:
        with URL_OPENER.open(request, timeout=120) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        if absent_ok and error.code == 404:
            return None
        raise ValueError(f'GitHub API {method} failed with HTTP {error.code}; no automatic overwrite/retry') from None


def gate():
    env = api(f'/repos/{REPO}/environments/{ENVIRONMENT}')
    require(env.get('can_admins_bypass') is False, 'Environment must disable admin bypass')
    policy = env.get('deployment_branch_policy') or {}
    require(policy.get('custom_branch_policies') is True and policy.get('protected_branches') is False, 'Environment must select main explicitly')
    rules = env.get('protection_rules', [])
    reviews = [rule for rule in rules if rule.get('type') == 'required_reviewers']
    require(len(reviews) == 1, 'Environment must require owner approval')
    reviewers = reviews[0].get('reviewers', [])
    require(len(reviewers) == 1 and reviewers[0].get('type') == 'User' and reviewers[0].get('reviewer', {}).get('login') == 'JayMandava', 'Environment reviewer must be JayMandava')
    require(reviews[0].get('prevent_self_review') is False, 'Use the explicitly approved sole-owner review policy')
    branches = api(f'/repos/{REPO}/environments/{ENVIRONMENT}/deployment-branch-policies')
    policies = branches.get('branch_policies', [])
    require(branches.get('total_count') == 1 and len(policies) == 1 and policies[0].get('name') == 'main' and policies[0].get('type') == 'branch', 'Environment must allow only main, not tags or patterns')


def preflight(version, commit, operation):
    version_tuple(version)
    require(re.fullmatch('[0-9a-f]{40}', commit), 'Provide a full lowercase commit SHA')
    require(operation in ('validate', 'publish'), 'Invalid operation')
    require(os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch', 'Manual dispatch required')
    require(os.environ.get('GITHUB_REPOSITORY') == REPO, 'Wrong repository')
    require(os.environ.get('GITHUB_REF') == 'refs/heads/main', 'Run only from main')
    require(os.environ.get('GITHUB_SHA') == commit, 'Input commit must equal the dispatched main commit')
    require(os.environ.get('GITHUB_WORKFLOW_SHA') == commit, 'Workflow and source must have the same commit')
    require(os.environ.get('GITHUB_ACTOR') == 'JayMandava' and os.environ.get('GITHUB_TRIGGERING_ACTOR') == 'JayMandava', 'Only owner-dispatched runs and reruns are allowed')
    require(api(f'/repos/{REPO}/git/ref/heads/main')['object']['sha'] == commit, 'Main moved; dispatch a new reviewed candidate')
    actual, code = source_version(Path('app/app/build.gradle.kts').read_text())
    require(actual == version, 'Input version differs from source')
    require(run('git', 'rev-parse', 'HEAD') == commit, 'Checked-out source differs from dispatch')
    run('git', 'diff', '--exit-code', 'HEAD')
    if operation == 'publish':
        gate()  # Check existence/protection before any environment job is scheduled.
        require(api(f'/repos/{REPO}/git/ref/tags/v{version}', absent_ok=True) is None, 'Tag already exists; refusing overwrite')
        require(api(f'/repos/{REPO}/releases/tags/v{version}', absent_ok=True) is None, 'Release already exists; refusing overwrite')
        latest = api(f'/repos/{REPO}/releases/latest')
        old_tag = latest['tag_name']
        require(old_tag.startswith('v') and version_tuple(version) > version_tuple(old_tag[1:]), 'Version must increase from latest release')
        import base64
        old = api(f'/repos/{REPO}/contents/app/app/build.gradle.kts?ref={urllib.parse.quote(old_tag, safe="")}')
        old_version, old_code = source_version(base64.b64decode(old['content']).decode())
        require(old_version == old_tag[1:] and code > old_code, 'versionCode must increase from the latest released source')
    if operation == 'publish':
        require(Path(f'docs/releases/{version}.md').is_file(), 'Reviewed versioned release notes missing')
    return code


def run(*args):
    result = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    require(result.returncode == 0, 'APK verification command failed: ' + args[0])
    return result.stdout.strip()


def verify_assets(archive):
    names = archive.namelist()
    require(len(names) == len(set(names)), 'Duplicate ZIP entries forbidden')
    actual = {name[7:]: hashlib.sha256(archive.read(name)).hexdigest()
              for name in names if name.startswith('assets/') and not name.endswith('/')}
    require({k: v for k, v in actual.items() if k in PUBLIC_ASSETS} == PUBLIC_ASSETS,
            'APK public asset hashes differ from reviewed baseline')
    require(set(actual) - set(PUBLIC_ASSETS) <= GENERATED_ASSETS,
            'Unexpected APK asset; no private font or overlay is allowed')


def verify_native(archive, sdk):
    libraries = [n for n in archive.namelist() if n.startswith('lib/') and n.endswith('.so')]
    require(set(libraries) == {'lib/arm64-v8a/' + n for n in NATIVE_LIBRARIES},
            'Unexpected native library set or ABI')
    readelf = sdk / 'ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf'
    with tempfile.TemporaryDirectory(prefix='nano-elf-') as scratch:
        for name in libraries:
            path = Path(scratch) / Path(name).name
            path.write_bytes(archive.read(name))
            require('AArch64' in run(str(readelf), '-h', str(path)), 'Non-AArch64 ELF')
            headers = run(str(readelf), '--wide', '--program-headers', str(path))
            alignment = [int(line.split()[-1], 16) for line in headers.splitlines()
                         if line.strip().startswith('LOAD ')]
            require(alignment and all(a >= 16384 and a & (a - 1) == 0 for a in alignment), 'Invalid ELF LOAD alignment or below 16 KB')


def unsigned_structure(data):
    end = data.rfind(b'PK\x05\x06', max(0, len(data) - 65557))
    require(end >= 0, 'Missing ZIP end record')
    central = struct.unpack_from('<I', data, end + 16)[0]
    require(0 < central < len(data) and data[central:central+4] == b'PK\x01\x02', 'Invalid ZIP directory')
    require(data[central-16:central] != b'APK Sig Block 42', 'Unsigned APK contains a signing block')


def verify_apk(path, version, code, signed):
    sdk = Path(os.environ['ANDROID_HOME'])
    build_tools = sdk / 'build-tools/35.0.0'
    badging = run(str(build_tools / 'aapt'), 'dump', 'badging', str(path))
    require(re.search(r"^package: name='ai.nanosearch.launcher' versionCode='" + re.escape(str(code)) + r"' versionName='" + re.escape(version) + "'", badging, re.M), 'Wrong application ID/version')
    require(re.search(r"^sdkVersion:'33'$", badging, re.M) and re.search(r"^targetSdkVersion:'35'$", badging, re.M), 'Unexpected SDK compatibility')
    require(re.findall(r"^native-code: (.+)$", badging, re.M) == ["'arm64-v8a'"], 'APK must contain arm64-v8a only')
    analyzer = sdk / 'cmdline-tools/19.0/bin/apkanalyzer'
    require(run(str(analyzer), 'manifest', 'debuggable', str(path)) == 'false', 'Debuggable APK forbidden')
    run(str(build_tools / 'zipalign'), '-c', '-P', '16', '4', str(path))
    with zipfile.ZipFile(path) as archive:
        require(archive.testzip() is None, 'Corrupt APK')
        require({name.split('/')[1] for name in archive.namelist() if name.startswith('lib/') and name.endswith('.so')} == {'arm64-v8a'}, 'Unexpected native library ABI')
        verify_assets(archive)
        verify_native(archive, sdk)
        if not signed:
            require(not any(re.match(r'META-INF/.*\.(RSA|DSA|EC|SF)$', name, re.I) for name in archive.namelist()), 'Unsigned candidate contains a JAR signature')
    if signed:
        output = run(str(build_tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(path))
        certs = re.findall(r'^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-f]+)$', output, re.M)
        require(certs == [CERT], 'APK must have exactly the existing production signer')
        require('Verified using v2 scheme (APK Signature Scheme v2): true' in output, 'APK v2 signature required')
    else:
        unsigned_structure(Path(path).read_bytes())
        result = subprocess.run([str(build_tools / 'apksigner'), 'verify', str(path)], text=True, capture_output=True)
        require(result.returncode != 0 and 'DOES NOT VERIFY' in result.stdout + result.stderr,
                'Unsigned candidate unexpectedly verifies or verifier failed to run')


def metadata(directory, version, commit, code, signed, expected_hash):
    directory = Path(directory)
    manifest = directory / 'candidate.json'
    apk_name = f'nano-search-{version}.apk' if signed else 'unsigned.apk'
    expected_files = {apk_name, 'candidate.json', 'release-notes.md'}
    if signed:
        expected_files.add(f'SHA256SUMS-{version}.txt')
    require(not directory.is_symlink() and {p.name for p in directory.iterdir()} == expected_files,
            'Unexpected handoff files')
    require(all(p.is_file() and not p.is_symlink() for p in directory.iterdir()), 'Non-regular handoff file')
    if expected_hash:
        require(sha(manifest) == expected_hash, 'Candidate manifest digest mismatch')
    value = json.loads(manifest.read_text())
    require(value.get('version') == version and value.get('commit') == commit and value.get('version_code') == code and value.get('signed') is signed, 'Artifact provenance differs from validated job outputs')
    require(value.get('apk') == apk_name and sha(directory / apk_name) == value.get('apk_sha256'), 'APK digest mismatch')
    require(sha(directory / 'release-notes.md') == value.get('notes_sha256'), 'Release notes digest mismatch')
    verify_apk(directory / apk_name, version, code, signed)
    if signed:
        require((directory / f'SHA256SUMS-{version}.txt').read_text() == f"{value['apk_sha256']}  {apk_name}\n", 'Checksum manifest mismatch')
    return value


def unsigned_source(version, code):
    root = Path('app/app/build/outputs/apk/release')
    value = json.loads((root / 'output-metadata.json').read_text())
    require(value.get('applicationId') == 'ai.nanosearch.launcher' and value.get('variantName') == 'release',
            'Unexpected APK output metadata')
    elements = value.get('elements', [])
    require(len(elements) == 1 and elements[0].get('outputFile') == 'app-release-unsigned.apk',
            'Expected one explicitly unsigned APK')
    require(elements[0].get('versionCode') == code and elements[0].get('versionName') == version,
            'Stale version in APK metadata')
    path = root / 'app-release-unsigned.apk'
    require(path.is_file() and not path.is_symlink(), 'Missing regular unsigned APK')
    verify_apk(path, version, code, False)
    return path


def collect(directory, version, commit, code):
    directory = Path(directory)
    require(directory.is_dir() and not directory.is_symlink() and not any(directory.iterdir()),
            'Candidate directory must be empty')
    apk = unsigned_source(version, code)
    notes = Path(f'docs/releases/{version}.md')
    require(notes.is_file() and not notes.is_symlink() and notes.stat().st_size <= 65536,
            'Reviewed regular release notes required')
    notes.read_text(encoding='utf-8')
    shutil.copyfile(apk, directory / 'unsigned.apk')
    shutil.copyfile(notes, directory / 'release-notes.md')
    return write_metadata(directory, version, commit, code, False)


def write_metadata(directory, version, commit, code, signed):
    directory = Path(directory)
    apk_name = f'nano-search-{version}.apk' if signed else 'unsigned.apk'
    value = dict(version=version, version_code=code, commit=commit, signed=signed, apk=apk_name, apk_sha256=sha(directory / apk_name), notes_sha256=sha(directory / 'release-notes.md'))
    (directory / 'candidate.json').write_text(json.dumps(value, sort_keys=True) + '\n')
    if signed:
        (directory / f'SHA256SUMS-{version}.txt').write_text(f"{value['apk_sha256']}  {apk_name}\n")
    metadata(directory, version, commit, code, signed, None)
    return sha(directory / 'candidate.json')


def publish(directory, version, commit, code, expected_hash):
    require(preflight(version, commit, 'publish') == code, 'Version code changed')
    directory = Path(directory)
    metadata(directory, version, commit, code, True, expected_hash)
    # Creation only. No force, clobber, deletion, or automatic recovery of partial releases.
    api(f'/repos/{REPO}/git/refs', 'POST', {'ref': f'refs/tags/v{version}', 'sha': commit})
    release = api(f'/repos/{REPO}/releases', 'POST', dict(tag_name=f'v{version}', target_commitish=commit, name=f'Nano Search {version}', body=(directory / 'release-notes.md').read_text(), draft=True, prerelease=False))
    release_id = release['id']
    for name in (f'nano-search-{version}.apk', f'SHA256SUMS-{version}.txt'):
        data = (directory / name).read_bytes()
        asset = api(f'/repos/{REPO}/releases/{release_id}/assets?name={urllib.parse.quote(name, safe="")}', 'POST', data, binary=True)
        require(asset.get('state') == 'uploaded' and asset.get('size') == len(data) and asset.get('digest') == 'sha256:' + hashlib.sha256(data).hexdigest(), 'Uploaded asset digest verification failed; draft left for manual recovery')
    require(api(f'/repos/{REPO}/git/ref/tags/v{version}')['object']['sha'] == commit, 'Published tag target changed')
    result = api(f'/repos/{REPO}/releases/{release_id}', 'PATCH', {'draft': False, 'make_latest': 'true'})
    require(result.get('draft') is False and result.get('tag_name') == f'v{version}', 'Publication outcome uncertain; inspect before retrying')
    print(result['html_url'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['preflight', 'collect', 'inspect', 'prepare', 'verify', 'publish'])
    parser.add_argument('--version', required=True)
    parser.add_argument('--commit', required=True)
    parser.add_argument('--operation', choices=['validate', 'publish'], default='validate')
    parser.add_argument('--code', type=int)
    parser.add_argument('--directory', default='candidate')
    parser.add_argument('--signed', action='store_true')
    parser.add_argument('--manifest-sha')
    args = parser.parse_args()
    version_tuple(args.version)
    require(re.fullmatch('[0-9a-f]{40}', args.commit), 'Invalid commit SHA')
    if args.command == 'preflight':
        value = preflight(args.version, args.commit, args.operation)
        print(f'Validated {args.operation} candidate {args.version} ({value}) at {args.commit}')
        if os.environ.get('GITHUB_OUTPUT'):
            with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
                output.write(f'version_code={value}\n')
    else:
        require(args.code is not None and args.code > 0, 'Version code required')
        if args.command in ('collect', 'inspect'):
            require(not args.signed, 'Unsigned build inspection cannot select signed input')
        if args.command in ('prepare', 'collect'):
            digest = (collect(args.directory, args.version, args.commit, args.code)
                      if args.command == 'collect'
                      else write_metadata(args.directory, args.version, args.commit, args.code, args.signed))
            if os.environ.get('GITHUB_OUTPUT'):
                with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
                    output.write(f'manifest_sha={digest}\n')
            if os.environ.get('GITHUB_STEP_SUMMARY'):
                value = json.loads((Path(args.directory) / 'candidate.json').read_text())
                with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
                    summary.write(f'Nano {args.version} ({args.code}) at `{args.commit}`\n\n'
                                  f'APK SHA-256: `{value["apk_sha256"]}`\n\n'
                                  f'Artifact manifest SHA-256: `{digest}`\n\n'
                                  f'Signed: `{args.signed}`; production certificate pin: `{CERT}`\n')
        elif args.command == 'inspect':
            path = unsigned_source(args.version, args.code)
            print('Verified unsigned APK SHA-256: ' + sha(path))
        elif args.command == 'verify':
            require(bool(args.manifest_sha), 'Expected manifest digest required')
            metadata(args.directory, args.version, args.commit, args.code, args.signed, args.manifest_sha)
        else:
            require(args.signed, 'Publication requires explicit --signed verification')
            require(bool(args.manifest_sha), 'Expected manifest digest required')
            publish(args.directory, args.version, args.commit, args.code, args.manifest_sha)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError, zipfile.BadZipFile) as error:
        print(f'Release check failed: {error}', file=sys.stderr)
        sys.exit(1)
