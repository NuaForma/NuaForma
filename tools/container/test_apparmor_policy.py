"""The candidate policy is explicit, bounded and not an enforcement bypass."""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]


class AppArmorPolicyTest(unittest.TestCase):
    def test_explicit_mount_rules_and_no_policy_bypass(self):
        policy = (ROOT / "deployment/pdf-runtime/nuaforma.apparmor").read_text()
        rules = [line.strip() for line in policy.splitlines() if line.strip().startswith("mount ")]
        self.assertTrue(rules)
        self.assertTrue(all("-> /" in rule and "options=" in rule for rule in rules))
        self.assertNotIn("mount,", [line.strip() for line in policy.splitlines()])
        self.assertNotIn("complain", policy)
        self.assertNotIn("change_profile", policy)
        self.assertNotIn("fstype=proc", policy)
        self.assertNotIn(" ux", policy)
        for forbidden in ("application", "extensions", "custom-fonts", "/oldroot/**"):
            self.assertFalse(any(forbidden in rule for rule in rules), forbidden)
        self.assertIn("deny /sys/** wklx,", policy)
        self.assertIn("deny network vsock,", policy)
        self.assertIn("deny network alg,", policy)


if __name__ == "__main__":
    unittest.main()
