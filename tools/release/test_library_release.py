"""New release helpers preserve exact-version, application and full artifact gates."""
import copy
import hashlib
import io
import pathlib
import tempfile
import unittest
from test_multiarch_release import reviewed
from validate_library_release import check
from verify_libraries import MODULES
from central_portal import upload


class LibraryReleaseTest(unittest.TestCase):
    def test_both_applications_and_all_artifacts_are_required(self):
        server, playground = reviewed(), reviewed()
        server['application'], playground['application'] = 'server', 'playground'
        server['approved'] = {'libraryAudit': True}
        server['libraryHashes'] = {module + '-' + server['version'] + suffix: 'a' * 64 for module in MODULES
                                  for suffix in ('.pom', '.jar', '-sources.jar', '-javadoc.jar')}
        check(server, playground, server['version'], server['sourceCommit'])
        broken = copy.deepcopy(server)
        broken['libraryHashes'].pop(next(iter(broken['libraryHashes'])))
        with self.assertRaises(ValueError):
            check(broken, playground, server['version'], server['sourceCommit'])
        broken = copy.deepcopy(playground)
        broken['application'] = 'server'
        with self.assertRaises(ValueError):
            check(server, broken, server['version'], server['sourceCommit'])
        broken = copy.deepcopy(playground)
        broken['sourceCommit'] = 'b' * 40
        with self.assertRaises(ValueError):
            check(server, broken, server['version'], server['sourceCommit'])

    def test_new_candidate_upload_is_still_explicit_and_never_publishes(self):
        with tempfile.TemporaryDirectory() as directory:
            bundle = pathlib.Path(directory) / 'fixture.zip'
            bundle.write_bytes(b'synthetic bundle')
            requests = []
            def opener(request, timeout):
                requests.append(request)
                response = io.BytesIO(b'12345678-1234-1234-1234-123456789abc')
                response.status = 201
                return response
            result = upload(bundle, '0.2.0', hashlib.sha256(bundle.read_bytes()).hexdigest(), 'synthetic-only', opener)
            self.assertFalse(result['published'])
            self.assertEqual(1, len(requests))
            self.assertIn('USER_MANAGED', requests[0].full_url)


if __name__ == '__main__':
    unittest.main()
