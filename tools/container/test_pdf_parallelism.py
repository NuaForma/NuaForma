"""Operator-only PDF concurrency parsing uses the real entry point without starting a JVM."""
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(os.name == 'posix' and shutil.which('sh'), 'Linux runtime entry point')
class PdfParallelismTest(unittest.TestCase):
    def launch(self, value=None, application='server'):
        with tempfile.TemporaryDirectory(prefix='nf-parallelism-') as directory:
            directory = Path(directory)
            java = directory / 'java'
            java.write_text('#!/bin/sh\nprintf "%s\\n" "$@"\n')
            java.chmod(0o700)
            script = (ROOT / 'deployment/pdf-runtime/start-server.sh').read_text()
            self.assertEqual(script.count('exec /opt/java/server/bin/java'), 1)
            launcher = directory / 'entry.sh'
            launcher.write_text(script.replace('/opt/java/server/bin/java', shlex.quote(str(java))))
            environment = {'PATH': '/usr/bin:/bin', 'NUAFORMA_APPLICATION': application}
            if value is not None:
                environment['NUAFORMA_PDF_PARALLELISM'] = value
            return subprocess.run(['sh', str(launcher)], env=environment, capture_output=True, text=True, timeout=10)

    def test_default_and_bounded_values_reach_both_applications(self):
        for application in ['server', 'playground']:
            for value in [None, '1', '2', '8']:
                result = self.launch(value, application)
                self.assertEqual(result.returncode, 0, result.stderr)
                flags = [line for line in result.stdout.splitlines() if line.startswith('-Dnuaforma.pdf.parallelism=')]
                self.assertEqual(flags, ['-Dnuaforma.pdf.parallelism=' + (value or '1')])
                self.assertIn('dev.nuaforma.' + application + '.Main', result.stdout)

    def test_invalid_and_empty_values_fail_before_start_without_echoing_values(self):
        for value in ['', '0', '9', '-1', '2.0', ' 2 ', 'secret-setting']:
            result = self.launch(value)
            self.assertEqual(result.returncode, 64)
            self.assertEqual(result.stdout, '')
            self.assertIn('Invalid NUAFORMA_PDF_PARALLELISM', result.stderr)
            self.assertNotIn('secret-setting', result.stderr)


if __name__ == '__main__':
    unittest.main()
