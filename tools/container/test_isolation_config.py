"""Default runtime profiles retain the ordinary container boundary."""
import copy
import json
import unittest
from verify_isolation_config import MASKED, READONLY, verify
from qualify_service import Runtime


def configured():
    return {"Config": {"User": "1000:1000"}, "AppArmorProfile": "docker-default",
            "HostConfig": {"ReadonlyRootfs": True, "Init": True, "Privileged": False,
                           "CapDrop": ["ALL"], "CapAdd": [], "PidMode": "", "NetworkMode": "bridge",
                           "SecurityOpt": ["no-new-privileges"],
                           "MaskedPaths": list(MASKED), "ReadonlyPaths": list(READONLY)}}


class IsolationConfigTest(unittest.TestCase):
    def test_intact_outer_boundary(self):
        verify(configured())

    def test_security_bypasses_rejected(self):
        for key, value in (("Privileged", True), ("CapAdd", ["SYS_ADMIN"]), ("CapDrop", []),
                           ("PidMode", "host"), ("NetworkMode", "host"), ("MaskedPaths", []),
                           ("ReadonlyPaths", []), ("SecurityOpt", ["seccomp=unconfined"]),
                           ("SecurityOpt", ["no-new-privileges", "systempaths=unconfined"]),
                           ("SecurityOpt", ["no-new-privileges", "seccomp=custom"]),
                           ("SecurityOpt", ["no-new-privileges", "apparmor=nuaforma-runtime"]),
                           ("ReadonlyRootfs", False), ("Init", False)):
            record = copy.deepcopy(configured())
            record["HostConfig"][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                verify(record)
        record = configured()
        record["Config"]["User"] = "root"
        with self.assertRaises(ValueError):
            verify(record)
        record = configured()
        record["AppArmorProfile"] = "unconfined"
        with self.assertRaises(ValueError):
            verify(record)

    def test_hosts_without_apparmor_need_no_named_policy(self):
        record = configured()
        record["AppArmorProfile"] = ""
        verify(record)
        with self.assertRaises(ValueError):
            verify(configured(), "nuaforma-runtime")

    def test_actual_process_state_must_confirm_active_defaults_and_removed_components(self):
        # Docker is the external boundary; no document processing is replaced by this unit test.
        class ObservedRuntime(Runtime):
            def __init__(self, state, removed='default-boundary'):
                self.name, self.state, self.removed = 'owned-test', state, removed
            def command(self, *arguments, timeout=45):
                if arguments[0] == 'inspect':
                    record = configured()
                    record['Config']['Labels'] = {'dev.nuaforma.validation-boundary': 'container'}
                    return json.dumps([record])
                return self.state if arguments[-1] == '/proc/self/status' else self.removed
        valid = 'Seccomp:\t2\nNoNewPrivs:\t1\nCapEff:\t0000000000000000\n'
        ObservedRuntime(valid).verify_default_boundary()
        for invalid in [valid.replace('Seccomp:\t2', 'Seccomp:\t0'), valid.replace('NoNewPrivs:\t1', 'NoNewPrivs:\t0'),
                        valid.replace('CapEff:\t0000000000000000', 'CapEff:\t0000000000000001')]:
            with self.assertRaises(AssertionError): ObservedRuntime(invalid).verify_default_boundary()
        with self.assertRaises(AssertionError): ObservedRuntime(valid, '/usr/local/bin/bwrap').verify_default_boundary()


if __name__ == "__main__":
    unittest.main()
