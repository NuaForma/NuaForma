"""The qualification sandbox must expose exactly the production device boundary."""
import pathlib
import re
import shlex
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]


class HeadlessDevicesTest(unittest.TestCase):
    def test_production_and_probe_use_the_same_minimal_devices(self):
        expected = ["--tmpfs", "/dev", "--dir", "/dev/shm"]
        for device in ("null", "zero", "full", "random", "urandom"):
            expected.extend(["--dev-bind", "/dev/" + device, "/dev/" + device])
        expected.extend(["--tmpfs", "/tmp"])
        for relative in ("deployment/pdf-runtime/validate-pdf.sh", "tools/pdf-evaluation/run_sandbox_probe.sh"):
            with self.subTest(launcher=relative):
                source = (ROOT / relative).read_text().replace("\\\n", " ")
                devices = re.search(r"--tmpfs /dev .*?--tmpfs /tmp", source)
                self.assertIsNotNone(devices)
                self.assertEqual(expected, shlex.split(devices.group()))
                tokens = shlex.split(source)
                self.assertNotIn("--dev", tokens)
                self.assertNotIn("--dev-bind-try", tokens)
                self.assertNotIn("/dev/pts", tokens)
                self.assertNotIn("/dev/ptmx", tokens)
                self.assertNotIn("/dev/tty", tokens)
                for option in ("--unshare-user", "--unshare-pid", "--unshare-net", "--unshare-ipc",
                               "--unshare-uts", "--clearenv", "--new-session", "--die-with-parent"):
                    self.assertIn(option, tokens)
                self.assertIn("--seccomp 3", source)
                self.assertIn("--cap-drop ALL", source)


if __name__ == "__main__":
    unittest.main()
