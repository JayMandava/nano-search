import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('nano_release', ROOT / 'tools/release/release.py')
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
tool_spec = importlib.util.spec_from_file_location('nano_install_tools', ROOT / 'tools/release/install_tools.py')
installer = importlib.util.module_from_spec(tool_spec)
tool_spec.loader.exec_module(installer)


def gate_fixture():
    return {'can_admins_bypass': False,
            'deployment_branch_policy': {'custom_branch_policies': True, 'protected_branches': False},
            'protection_rules': [{'type': 'required_reviewers', 'prevent_self_review': False,
                                  'reviewers': [{'type': 'User', 'reviewer': {'login': 'JayMandava'}}]}]}


class GateTests(unittest.TestCase):
    def check(self, environment=None, branches=None):
        if environment is None:
            environment = gate_fixture()
        if branches is None:
            branches = {'total_count': 1, 'branch_policies': [{'name': 'main', 'type': 'branch'}]}
        with patch.object(release, 'api', side_effect=[environment, branches]):
            release.gate()

    def test_exact_owner_main_policy(self):
        self.check()

    def test_no_admin_bypass(self):
        env = gate_fixture(); env['can_admins_bypass'] = True
        with self.assertRaisesRegex(ValueError, 'admin bypass'):
            self.check(env)

    def test_no_additional_reviewer(self):
        env = gate_fixture()
        env['protection_rules'][0]['reviewers'].append({'type': 'User', 'reviewer': {'login': 'other'}})
        with self.assertRaisesRegex(ValueError, 'reviewer'):
            self.check(env)

    def test_owner_must_be_able_to_review_own_dispatch(self):
        env = gate_fixture(); env['protection_rules'][0]['prevent_self_review'] = True
        with self.assertRaisesRegex(ValueError, 'sole-owner'):
            self.check(env)

    def test_no_tag_or_wildcard_policy(self):
        for policy in [{'name': 'main', 'type': 'tag'}, {'name': 'main*', 'type': 'branch'}]:
            with self.subTest(policy=policy), self.assertRaisesRegex(ValueError, 'only main'):
                self.check(branches={'total_count': 1, 'branch_policies': [policy]})

    def test_missing_environment_fails_without_creating_one(self):
        with patch.object(release, 'api', side_effect=ValueError('HTTP 404')) as call:
            with self.assertRaisesRegex(ValueError, '404'):
                release.gate()
            self.assertEqual(call.call_count, 1)
            self.assertEqual(call.call_args.args, ('/repos/JayMandava/nano-search/environments/production-release',))

    def test_untrusted_event_stops_before_network(self):
        with patch.dict(os.environ, {'GITHUB_EVENT_NAME': 'pull_request'}, clear=True), patch.object(release, 'api') as api:
            with self.assertRaisesRegex(ValueError, 'Manual dispatch'):
                release.preflight('0.8.2', 'a' * 40, 'publish')
            api.assert_not_called()

    def test_stale_workflow_commit_stops_before_network(self):
        env = {'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_REPOSITORY': release.REPO,
               'GITHUB_REF': 'refs/heads/main', 'GITHUB_SHA': 'a'*40, 'GITHUB_WORKFLOW_SHA': 'b'*40}
        with patch.dict(os.environ, env, clear=True), patch.object(release, 'api') as api:
            with self.assertRaisesRegex(ValueError, 'same commit'):
                release.preflight('0.8.2', 'a' * 40, 'publish')
            api.assert_not_called()

    def test_non_owner_stops_before_network(self):
        env = {'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_REPOSITORY': release.REPO,
               'GITHUB_REF': 'refs/heads/main', 'GITHUB_SHA': 'a'*40, 'GITHUB_WORKFLOW_SHA': 'a'*40,
               'GITHUB_ACTOR': 'other', 'GITHUB_TRIGGERING_ACTOR': 'other'}
        with patch.dict(os.environ, env, clear=True), patch.object(release, 'api') as api:
            with self.assertRaisesRegex(ValueError, 'Only owner'):
                release.preflight('0.8.2', 'a'*40, 'publish')
            api.assert_not_called()


class AssetTests(unittest.TestCase):
    def archive(self, change=None, extra=None, duplicate=None):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w') as out:
            for name in release.PUBLIC_ASSETS:
                data = (ROOT / 'app/app/src/main/assets' / name).read_bytes()
                out.writestr('assets/' + name, b'changed' if name == change else data)
            for name, data in (extra or {}).items():
                out.writestr('assets/' + name, data)
            if duplicate:
                import warnings
                with warnings.catch_warnings():
                    warnings.simplefilter('ignore', UserWarning)
                    out.writestr('assets/' + duplicate, b'duplicate')
        stream.seek(0)
        return zipfile.ZipFile(stream)

    def test_reviewed_assets_and_generated_profiles(self):
        with self.archive(extra={'dexopt/baseline.prof': b'generated'}) as archive:
            release.verify_assets(archive)

    def test_renamed_extra_content_is_rejected(self):
        with self.archive(extra={'innocent-name.bin': b'not-a-private-font'}) as archive:
            with self.assertRaisesRegex(ValueError, 'Unexpected APK asset'):
                release.verify_assets(archive)

    def test_replacement_public_font_rejected_by_hash(self):
        with self.archive(change='Barlow-SemiBold.ttf') as archive:
            with self.assertRaisesRegex(ValueError, 'hashes'):
                release.verify_assets(archive)

    def test_duplicate_asset_rejected(self):
        with self.archive(duplicate='places.tsv') as archive:
            with self.assertRaisesRegex(ValueError, 'Duplicate'):
                release.verify_assets(archive)


class ArtifactTests(unittest.TestCase):
    def test_manifest_tamper_stops_before_apk_tools(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp); (path/'candidate.json').write_text('{}')
            (path/'unsigned.apk').write_bytes(b'fixture')
            (path/'release-notes.md').write_text('Fixture notes')
            with patch.object(release, 'verify_apk') as tools:
                with self.assertRaisesRegex(ValueError, 'digest mismatch'):
                    release.metadata(path, '0.8.2', 'a'*40, 17, False, '0'*64)
                tools.assert_not_called()

    def test_symlink_handoff_rejected_before_reading_target(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp); (path/'candidate.json').symlink_to('/definitely-not-a-real-secret')
            (path/'unsigned.apk').write_bytes(b'fixture')
            (path/'release-notes.md').write_text('Fixture notes')
            with self.assertRaisesRegex(ValueError, 'Non-regular'):
                release.metadata(path,'0.8.2','a'*40,17,False,None)

    def test_wrong_and_multiple_certificates_rejected(self):
        for certificates in [['0'*64], [release.CERT, release.CERT]]:
            with self.subTest(certificates=certificates), tempfile.TemporaryDirectory() as tmp:
                path=Path(tmp)/'test.apk'
                with zipfile.ZipFile(path,'w') as out:
                    out.writestr('lib/arm64-v8a/libnanollm.so', b'fixture')
                def command(*args):
                    if args[0].endswith('/aapt'):
                        return "package: name='ai.nanosearch.launcher' versionCode='17' versionName='0.8.2'\nsdkVersion:'33'\ntargetSdkVersion:'35'\nnative-code: 'arm64-v8a'"
                    if args[0].endswith('/apkanalyzer'): return 'false'
                    if args[0].endswith('/apksigner'):
                        return '\n'.join(f'Signer #{i} certificate SHA-256 digest: {c}' for i,c in enumerate(certificates,1))+'\nVerified using v2 scheme (APK Signature Scheme v2): true'
                    return ''
                with patch.dict(os.environ, {'ANDROID_HOME': tmp}), patch.object(release,'run',side_effect=command), patch.object(release,'verify_assets'), patch.object(release,'verify_native'):
                    with self.assertRaisesRegex(ValueError, 'exactly the existing production signer'):
                        release.verify_apk(path,'0.8.2',17,True)

    def test_version_and_input_validation(self):
        self.assertEqual(release.source_version('versionName = "0.8.2"\nversionCode = 17\n'), ('0.8.2',17))
        for version in ['../x', '01.2.3', '0.8.2; echo unsafe', '0.8.2-rc1']:
            with self.assertRaises(ValueError): release.version_tuple(version)

    def test_workflow_privilege_contract(self):
        text=(ROOT/'.github/workflows/release.yml').read_text()
        self.assertNotIn('pull_request',text)
        self.assertNotIn('continue-on-error',text)
        self.assertIn('permissions: {}',text)
        self.assertEqual(text.count('contents: write'),1)
        jobs=text.split('\n  sign:\n',1)
        self.assertEqual(len(jobs),2)
        self.assertNotIn('${{ secrets.',jobs[0])
        sign=jobs[1].split('\n  publish:\n',1)[0]
        self.assertNotIn('gradlew',sign)
        self.assertIn('environment: production-release',sign)
        self.assertIn('--debuggable-apk-permitted false',sign)
        self.assertIn(release.CERT,sign)
        validate=text.split('\n  validate:\n',1)[1].split('\n  build_unsigned:\n',1)[0]
        self.assertNotIn('upload-artifact',validate)
        self.assertNotIn('environment:',validate)
        build=text.split('\n  build_unsigned:\n',1)[1].split('\n  sign:\n',1)[0]
        self.assertIn('environment: production-release',build)
        self.assertIn('-PunsignedRelease=true -PincludePersonalOverlay=false',build)
        import re
        actions=re.findall(r'uses: ([^\s]+)',text)
        self.assertTrue(actions and all(re.fullmatch(r'actions/[a-z-]+@[0-9a-f]{40}',a) for a in actions))


class InstallerGuardTests(unittest.TestCase):
    def test_no_local_install_or_download_when_not_in_github_actions(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(installer.urllib.request, 'build_opener') as network:
            with self.assertRaisesRegex(ValueError, 'GitHub-hosted'):
                installer.install(False)
            network.assert_not_called()

    def test_missing_existing_sdk_acceptance_stops_before_network(self):
        with tempfile.TemporaryDirectory() as tmp:
            env = {'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'github-hosted', 'RUNNER_OS': 'Linux', 'RUNNER_ARCH': 'X64', 'ANDROID_HOME': tmp}
            with patch.dict(os.environ, env, clear=True), patch.object(installer.urllib.request, 'build_opener') as network:
                with self.assertRaisesRegex(ValueError, 'license acceptance'):
                    installer.install(False)
                network.assert_not_called()

    def test_self_hosted_runner_cannot_reach_key_preparation(self):
        with patch.dict(os.environ, {'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'self-hosted'}, clear=True):
            with self.assertRaisesRegex(ValueError, 'Fresh GitHub-hosted'):
                installer.install(True)


if __name__ == '__main__':
    unittest.main()
