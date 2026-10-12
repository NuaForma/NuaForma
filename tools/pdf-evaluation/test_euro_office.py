"""Owned-process boundary tests; mocked output is not rendering evidence."""
import pathlib
import subprocess
import unittest
from unittest.mock import patch

from euro_office import owned_run


class EuroOfficeBoundaryTest(unittest.TestCase):
    @patch('euro_office.subprocess.run')
    def test_direct_converter_has_no_network_privileges_or_host_repository(self, run):
        owned_run(pathlib.Path('docker'), 'immutable-image', ['/work/params.xml'],
                  [(pathlib.Path('private-input'), '/input', True)])
        command = run.call_args.args[0]
        self.assertIn('--read-only', command)
        self.assertIn('no-new-privileges', command)
        self.assertEqual('none', command[command.index('--network') + 1])
        self.assertEqual('1000:1000', command[command.index('--user') + 1])
        self.assertNotIn('--privileged', command)
        self.assertNotIn('--publish', command)
        self.assertEqual(120, run.call_args.kwargs['timeout'])
        self.assertTrue(command[command.index('--mount') + 1].endswith(',readonly'))

    @patch('euro_office.uuid.uuid4', return_value='fixed-identity')
    @patch('euro_office.subprocess.run')
    def test_package_volume_is_read_only_and_scoped(self, run, identity):
        owned_run(pathlib.Path('docker'), 'image', [], [('private-package-volume', '/var/www/euro-office', True)])
        command = run.call_args.args[0]
        mount = command[command.index('--mount') + 1]
        self.assertEqual('type=volume,source=private-package-volume,target=/var/www/euro-office,volume-subpath=var/www/euro-office,readonly', mount)

    @patch('euro_office.uuid.uuid4', return_value='fixed-identity')
    @patch('euro_office.subprocess.run')
    def test_timeout_removes_only_matching_owned_container(self, run, identity):
        run.side_effect = [subprocess.TimeoutExpired('docker', 120),
                           subprocess.CompletedProcess([], 0, 'fixed-identity\n', ''),
                           subprocess.CompletedProcess([], 0, '', '')]
        with self.assertRaises(subprocess.TimeoutExpired):
            owned_run(pathlib.Path('docker'), 'image', [], [])
        self.assertEqual(['docker', 'rm', '--force', 'nuaforma-euro-qa-fixed-identity'], run.call_args.args[0])

    @patch('euro_office.uuid.uuid4', return_value='fixed-identity')
    @patch('euro_office.subprocess.run')
    def test_timeout_does_not_remove_unowned_container(self, run, identity):
        run.side_effect = [subprocess.TimeoutExpired('docker', 120),
                           subprocess.CompletedProcess([], 0, 'someone-else\n', '')]
        with self.assertRaises(subprocess.TimeoutExpired):
            owned_run(pathlib.Path('docker'), 'image', [], [])
        self.assertEqual(2, run.call_count)


if __name__ == '__main__':
    unittest.main()
