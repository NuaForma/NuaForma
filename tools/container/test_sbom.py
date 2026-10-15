import hashlib
import importlib.util
import pathlib
import tempfile
import unittest


spec = importlib.util.spec_from_file_location('normalize_runtime', pathlib.Path(__file__).parents[1] / 'sbom/normalize_runtime.py')
normalizer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(normalizer)


class SbomBuildTest(unittest.TestCase):
    def test_resolved_file_hashes_scope_graph_and_private_build_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = pathlib.Path(directory) / 'example.jar'
            jar.write_bytes(b'actual artifact')
            raw = dict(bomFormat='CycloneDX', specVersion='1.7', metadata=dict(component={'bom-ref':'root'}, timestamp='volatile'),
                       components=[{'bom-ref':'private-build-ref','group':'example','name':'library','version':'1','properties':[dict(name='path',value=directory)]}],
                       dependencies=[dict(ref='root',dependsOn=['private-build-ref']),dict(ref='private-build-ref',dependsOn=[])])
            bom = normalizer.normalize(raw,[dict(group='example',name='library',version='1',file=str(jar))],'nuaforma-server','0.1.0-alpha.1','unknown')
            component = bom['components'][0]
            self.assertEqual(component['hashes'],[dict(alg='SHA-256',content=hashlib.sha256(jar.read_bytes()).hexdigest())])
            self.assertEqual(component['purl'],'pkg:maven/example/library@1')
            self.assertNotIn('properties',component)
            self.assertNotIn('timestamp',bom['metadata'])
            self.assertEqual(bom['compositions'][0]['aggregate'],'incomplete')
            self.assertEqual(bom['metadata']['properties'][0]['value'],'jvm-runtime')
            self.assertTrue(any(component['bom-ref'] in edge['dependsOn'] for edge in bom['dependencies']))

    def test_missing_artifact_metadata_is_not_silently_dropped(self):
        raw = dict(bomFormat='CycloneDX',specVersion='1.7',metadata=dict(component={'bom-ref':'root'}),components=[])
        with self.assertRaises(AssertionError):
            normalizer.normalize(raw,[dict(group='missing',name='library',version='1',file='missing')],'nuaforma-server','0.1.0-alpha.1','unknown')


if __name__ == '__main__':
    unittest.main()
