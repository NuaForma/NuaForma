"""Keep optional Playground kits complete without changing retained runtime profiles."""
import pathlib
import tempfile
import unittest
import zipfile
from unittest.mock import patch

import package_complete_kit as complete
import package_deployment_kits as deployments
from build_client_collections import client_readme


class PackagingTest(unittest.TestCase):
    def test_collection_guides_default_to_the_matching_seven_scenario_release(self):
        for client in ('bruno', 'postman'):
            text = client_readme(client)
            self.assertIn('0.2.0-alpha.1', text)
            self.assertIn('12 requests', text)
            self.assertIn('all 7 document scenarios', text)
            self.assertNotIn('11 alpha.3-compatible requests', text)

    def fixture(self, root, playground):
        names = ['docker', 'docker-arm64', 'helm', 'bruno', 'postman']
        if playground:
            names.extend(['docker-playground', 'docker-playground-arm64'])
        for name in names:
            (root / name).mkdir()
            (root / name / 'README.md').write_text(name, encoding='utf-8')
        for name in ('START-HERE.md', 'EXAMPLES.md', 'scenarios.json', 'nuaforma-example-template.docx', '01-example.json'):
            (root / name).write_bytes(name.encode())

    def test_deployments_retain_profiles_with_and_without_playground(self):
        for playground in (False, True):
            with self.subTest(playground=playground), tempfile.TemporaryDirectory() as temporary:
                root = pathlib.Path(temporary)
                self.fixture(root, playground)
                deployments.package(root)
                names = ['docker', 'docker-arm64', 'helm']
                if playground:
                    names.extend(['docker-playground', 'docker-playground-arm64'])
                self.assertEqual(len(list(root.glob('*-kit.zip'))), len(names))
                for name in names:
                    with zipfile.ZipFile(root / (name + '-kit.zip')) as archive:
                        self.assertEqual(archive.read('seccomp.json'), (deployments.ROOT / 'deployment/pdf-runtime/seccomp.json').read_bytes())
                        self.assertEqual(archive.read('nuaforma.apparmor'), (deployments.ROOT / 'deployment/pdf-runtime/nuaforma.apparmor').read_bytes())
                        if name.startswith('docker'):
                            self.assertEqual(archive.read('compose.apparmor.yaml'), (deployments.ROOT / 'deployment/pdf-runtime/compose.apparmor.yaml').read_bytes())

    def test_complete_kit_optional_playground_layout_and_no_nested_archives(self):
        for playground in (False, True):
            with self.subTest(playground=playground), tempfile.TemporaryDirectory() as temporary:
                root = pathlib.Path(temporary)
                self.fixture(root, playground)
                with patch.object(complete, 'OUT', root):
                    complete.package()
                with zipfile.ZipFile(root / 'nuaforma-complete-kit.zip') as archive:
                    self.assertIn('Deploy/AMD64/README.md', archive.namelist())
                    self.assertIn('Deploy/ARM64/README.md', archive.namelist())
                    self.assertIn('Collections/Bruno/README.md', archive.namelist())
                    self.assertIn('Testkit/nuaforma-example-template.docx', archive.namelist())
                    self.assertEqual('Deploy/Playground/AMD64/README.md' in archive.namelist(), playground)
                    self.assertEqual('Deploy/Playground/ARM64/README.md' in archive.namelist(), playground)
                    self.assertFalse(any(name.endswith('.zip') for name in archive.namelist()))


if __name__ == '__main__':
    unittest.main()
