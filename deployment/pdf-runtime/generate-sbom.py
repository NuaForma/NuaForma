"""Build-only container inventory. Never installed or run in the final service."""
import argparse
import hashlib
import json
import pathlib
import re
import zipfile
import xml.etree.ElementTree as ET
from urllib.parse import quote


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inventory(root, architecture, application):
    jars = root / 'application/lib'
    main_jar = next(jars.glob(f'nuaforma-{application}-*.jar'))
    resource = 'META-INF/nuaforma/' + ('playground-sbom.json' if application == 'playground' else 'sbom.json')
    with zipfile.ZipFile(main_jar) as archive:
        bom = json.loads(archive.read(resource))
    app = bom['metadata']['component']
    components = {item['bom-ref']: item for item in bom['components']}
    app['hashes'] = [dict(alg='SHA-256', content=digest(main_jar))]
    components[app['bom-ref']] = app
    root_ref = f'urn:nuaforma:container:{application}:{architecture}:{app["version"]}'
    dependencies = {item['ref']: set(item.get('dependsOn', [])) for item in bom['dependencies']}
    dependencies[root_ref] = {app['bom-ref']}
    known = {item['file']: item for item in json.loads((root / 'application/third-party-materials/inventory.json').read_text())['components']}

    def jar_component(path, group, name, version, role, licenses=None, classifier=None):
        ref = f'pkg:maven/{quote(group, safe="")}/{quote(name, safe="")}@{quote(version, safe="")}'
        if role != 'application':
            ref += '?' + ('classifier=' + quote(classifier, safe='') + '&' if classifier else '') + 'runtime=' + role
        item = dict(type='library', group=group, name=name, version=version, purl=ref,
                    hashes=[dict(alg='SHA-256', content=digest(path))])
        item['bom-ref'] = ref
        if licenses:
            item['licenses'] = licenses
        components[ref] = item
        dependencies[root_ref].add(ref)

    # Assert that no stale or omitted app artifact survives distribution assembly.
    for path in jars.glob('*.jar'):
        actual_hash = digest(path)
        matching = [c for c in components.values() if any(h['content'] == actual_hash for h in c.get('hashes', []) if h['alg'] == 'SHA-256')]
        assert matching, f'Application artifact missing from BOM: {path.name}'
        assert path.name in known, f'Application license inventory missing: {path.name}'
        assert known[path.name]['sha256'] == actual_hash, f'Stale application material inventory: {path.name}'

    for path in sorted((root / 'pdf-validation').glob('*.jar')):
        artifact = known[path.name]
        source = next((c for c in components.values() if c.get('group') == artifact['group'] and c['name'] == artifact['artifact'] and c['version'] == artifact['version']), {})
        jar_component(path, artifact['group'], artifact['artifact'], artifact['version'], 'pdfbox-validator', source.get('licenses'))
    vera = json.loads((root / 'verapdf-validation/components.json').read_text())
    vera_materials = json.loads((root / 'verapdf-validation/materials/inventory.json').read_text())
    for artifact in vera:
        path = root / 'verapdf-validation' / artifact['file']
        licenses = [dict(license=dict(id='MPL-2.0'))] if artifact['group'] == 'org.verapdf' else None
        with zipfile.ZipFile(path) as archive:
            if any('pdfa-' in name.lower() and name.endswith('.xml') for name in archive.namelist()):
                licenses = [dict(expression='MPL-2.0 AND CC-BY-4.0')] if artifact['group'] == 'org.verapdf' else licenses
        if licenses is None:
            record = next((item for item in vera_materials if item.get('file') == artifact['file']), {})
            if not record.get('sources'):
                record = next((item for item in vera_materials if all(item.get(key) == artifact[key] for key in ('group','artifact','version')) and item.get('sources')), {})
            for source in record.get('sources', []):
                if source['path'].endswith('.pom'):
                    pom = ET.parse(root / 'verapdf-validation/materials' / source['path']).getroot()
                    ns = {'m':'http://maven.apache.org/POM/4.0.0'}
                    licenses = [dict(license={key:element.findtext('m:' + key, namespaces=ns) for key in ('name','url') if element.findtext('m:' + key, namespaces=ns)}) for element in pom.findall('m:licenses/m:license', ns)] or None
        jar_component(path, artifact['group'], artifact['artifact'], artifact['version'], 'verapdf-validator', licenses, artifact.get('classifier'))

    for role in ('outer',):
        architectures = {}
        for paragraph in (root / f'{role}-status').read_text().split('\n\n'):
            fields = dict(re.findall(r'^(Package|Architecture): (.+)$', paragraph, re.MULTILINE))
            if fields.get('Package'):
                architectures[fields['Package']] = fields.get('Architecture', architecture)
        for line in (root / f'{role}-packages.tsv').read_text().splitlines():
            name, version, source_name, source_version = line.split('\t')
            name = name.split(':')[0]
            assert name in architectures
            ref = f'pkg:deb/debian/{quote(name, safe="")}@{quote(version, safe="")}?arch={architectures[name]}&distro=debian-13&role={role}'
            item = dict(type='library', name=name, version=version, purl=ref, properties=[
                dict(name='nuaforma:component:role', value=role), dict(name='nuaforma:debian:source', value=source_name or name),
                dict(name='nuaforma:debian:sourceVersion', value=source_version or version)])
            item['bom-ref'] = ref
            components[ref] = item
            dependencies[root_ref].add(ref)
    for role in ('server', 'validator'):
        path = root / f'java-{role}-release'
        values = dict(re.findall(r'^([A-Z_]+)="(.*)"$', path.read_text(), re.MULTILINE))
        version = values['JAVA_RUNTIME_VERSION']
        ref = f'urn:nuaforma:java:{role}:{architecture}:{version}'
        components[ref] = {'type':'platform', 'bom-ref':ref, 'name':'Eclipse Temurin', 'version':version,
                           'licenses':[dict(expression='GPL-2.0-only WITH Classpath-exception-2.0')],
                           'properties':[dict(name='nuaforma:component:role', value=role), dict(name='nuaforma:java:releaseSha256', value=digest(path))]}
        dependencies[root_ref].add(ref)
    bom['metadata']['component'] = {'type':'container', 'bom-ref':root_ref, 'name':'nuaforma' + ('-playground' if application == 'playground' else ''), 'version':app['version']}
    bom['metadata']['properties'] = [item for item in bom['metadata']['properties'] if item['name'] not in ('nuaforma:inventory:scope','nuaforma:inventory:exclusions')] + [
        dict(name='nuaforma:inventory:scope', value='shipped-container'), dict(name='nuaforma:container:architecture', value=architecture),
        dict(name='nuaforma:inventory:exclusions', value='Operator extensions/fonts, request resources/data, host kernel, build/test tools; package-level native inventory is not exhaustive file-level provenance')]
    bom['components'] = sorted(components.values(), key=lambda item:item['bom-ref'])
    bom['dependencies'] = [dict(ref=ref, dependsOn=sorted(children)) for ref, children in sorted(dependencies.items())]
    bom['compositions'] = [dict(aggregate='incomplete', assemblies=[root_ref])]
    return bom


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=pathlib.Path, required=True)
    parser.add_argument('--architecture', choices=('amd64','arm64'), required=True)
    parser.add_argument('--application', choices=('server','playground'), required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    args.output.write_text(json.dumps(inventory(args.root, args.architecture, args.application), indent=2) + '\n', encoding='utf-8')
