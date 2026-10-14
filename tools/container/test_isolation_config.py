"""Reject weakening outer isolation even if the inner probe happens to pass."""
import copy
import unittest
from verify_isolation_config import MASKED, READONLY, verify


def configured():
    return {"Config": {"User": "1000:1000"}, "AppArmorProfile": "nuaforma-runtime",
            "HostConfig": {"ReadonlyRootfs": True, "Init": True, "Privileged": False,
                           "CapDrop": ["ALL"], "CapAdd": [], "PidMode": "", "NetworkMode": "bridge",
                           "SecurityOpt": ["no-new-privileges", "seccomp=reviewed", "apparmor=nuaforma-runtime"],
                           "MaskedPaths": list(MASKED), "ReadonlyPaths": list(READONLY)}}


class IsolationConfigTest(unittest.TestCase):
    def test_intact_outer_boundary(self):
        verify(configured(), "nuaforma-runtime")

    def test_security_bypasses_rejected(self):
        for key, value in (("Privileged", True), ("CapAdd", ["SYS_ADMIN"]), ("CapDrop", []),
                           ("PidMode", "host"), ("NetworkMode", "host"), ("MaskedPaths", []),
                           ("ReadonlyPaths", []), ("SecurityOpt", ["seccomp=unconfined"]),
                           ("SecurityOpt", ["no-new-privileges", "systempaths=unconfined"]),
                           ("ReadonlyRootfs", False), ("Init", False)):
            record = copy.deepcopy(configured())
            record["HostConfig"][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                verify(record, "nuaforma-runtime")
        record = configured()
        record["Config"]["User"] = "root"
        with self.assertRaises(ValueError):
            verify(record)
        record = configured()
        record["AppArmorProfile"] = "unconfined"
        with self.assertRaises(ValueError):
            verify(record, "nuaforma-runtime")


if __name__ == "__main__":
    unittest.main()
