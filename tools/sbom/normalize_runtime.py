"""Normalize the approved build-tool BOM against the actual production artifact files."""
import argparse
import hashlib
import json
import pathlib
import re
from urllib.parse import quote


def normalize(raw, artifacts, name, version, commit):
    assert raw['bomFormat'] == 'CycloneDX' and raw['specVersion'] == '1.7'
    assert re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?', version)
    assert commit == 'unknown' or re.fullmatch(r'[0-9a-f]{40}|[0-9a-f]{64}', commit)
    root_ref = f'pkg:maven/dev.nuaforma/{name}@{quote(version, safe="")}'
    originals = {(c.get('group'), c['name'], c.get('version')): c for c in raw.get('components', [])}
    refs = {raw['metadata']['component']['bom-ref']: root_ref}
    components = []
    for artifact in artifacts:
        key = artifact['group'], artifact['name'], artifact['version']
        source = originals.get(key)
        assert source is not None, f'Missing production artifact metadata: {key}'
        ref = f'pkg:maven/{quote(key[0], safe="")}/{quote(key[1], safe="")}@{quote(key[2], safe="")}'
        assert ref not in {c['bom-ref'] for c in components}, 'Ambiguous production artifact identity'
        refs[source['bom-ref']] = ref
        component = dict(type='library', group=key[0], name=key[1], version=key[2], purl=ref,
                         hashes=[dict(alg='SHA-256', content=hashlib.sha256(pathlib.Path(artifact['file']).read_bytes()).hexdigest())])
        component['bom-ref'] = ref
        if source.get('licenses'):
            component['licenses'] = source['licenses']
        if key[0] == 'dev.nuaforma':
            component['licenses'] = [dict(license=dict(id='Apache-2.0'))]
        components.append(component)
    component = dict(type='application', group='dev.nuaforma', name=name, version=version, purl=root_ref,
                     licenses=[dict(license=dict(id='Apache-2.0'))])
    component['bom-ref'] = root_ref
    component['externalReferences'] = [dict(type='vcs', url='https://github.com/NuaForma/NuaForma' + (f'/tree/{commit}' if commit != 'unknown' else ''))]
    dependencies = {}
    for edge in raw.get('dependencies', []):
        if edge['ref'] in refs:
            dependencies.setdefault(refs[edge['ref']], set()).update(refs[child] for child in edge.get('dependsOn', []) if child in refs)
    # The root also names the resolved runtime artifact set, including project nodes
    # that the build tool may have represented through non-artifact platform nodes.
    dependencies.setdefault(root_ref, set()).update(c['bom-ref'] for c in components)
    properties = [dict(name='nuaforma:inventory:scope', value='jvm-runtime'),
                  dict(name='nuaforma:inventory:exclusions', value='Build/test tools; operator extensions/fonts; request data/resources; host JVM and OS'),
                  dict(name='nuaforma:source:commit', value=commit)]
    return dict(bomFormat='CycloneDX', specVersion='1.7', version=1,
                metadata=dict(component=component, properties=properties), components=sorted(components, key=lambda c: c['bom-ref']),
                dependencies=[dict(ref=ref, dependsOn=sorted(children)) for ref, children in sorted(dependencies.items())],
                compositions=[dict(aggregate='incomplete', assemblies=[root_ref])])


def playground(server, server_jar):
    root = server['metadata']['component']
    ref = root['bom-ref']
    root['hashes'] = [dict(alg='SHA-256', content=hashlib.sha256(server_jar.read_bytes()).hexdigest())]
    server['components'].append(root)
    new_root = dict(root)
    new_root.pop('hashes')
    new_root['name'] = 'nuaforma-playground'
    new_root['bom-ref'] = new_root['purl'] = ref.replace('/nuaforma-server@', '/nuaforma-playground@')
    server['metadata']['component'] = new_root
    server['dependencies'].append(dict(ref=new_root['bom-ref'], dependsOn=[ref]))
    server['components'].sort(key=lambda c: c['bom-ref'])
    server['compositions'] = [dict(aggregate='incomplete', assemblies=[new_root['bom-ref']])]
    return server


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('raw', type=pathlib.Path)
    parser.add_argument('artifacts', type=pathlib.Path)
    parser.add_argument('output', type=pathlib.Path)
    parser.add_argument('--name')
    parser.add_argument('--version')
    parser.add_argument('--commit')
    parser.add_argument('--playground', action='store_true')
    args = parser.parse_args()
    bom = playground(json.loads(args.raw.read_text()), args.artifacts) if args.playground else normalize(json.loads(args.raw.read_text()), json.loads(args.artifacts.read_text()), args.name, args.version, args.commit)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(bom, ensure_ascii=True, indent=2) + '\n', encoding='utf-8')
