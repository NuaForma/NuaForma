"""Native shipped-SBOM and optional Playground checks; no scans or publication."""
import argparse
import hashlib
import json
import pathlib
import shutil
from qualify_service import Runtime, generate, http, template
from qualify_pdfa_policy import qualify as qualify_pdfa_policy
from qualify_template_links import qualify as qualify_template_links


def verify_bom(bom, architecture, application):
    assert bom['bomFormat'] == 'CycloneDX' and bom['specVersion'] == '1.7'
    props = {item['name']:item['value'] for item in bom['metadata']['properties']}
    assert props['nuaforma:inventory:scope'] == 'shipped-container'
    assert props['nuaforma:container:architecture'] == architecture
    root = bom['metadata']['component']
    assert root['name'] == ('nuaforma-playground' if application == 'playground' else 'nuaforma')
    components = bom['components']
    refs = {item['bom-ref'] for item in components} | {root['bom-ref']}
    assert len(refs) == len(components) + 1
    for edge in bom['dependencies']:
        assert edge['ref'] in refs and set(edge['dependsOn']) <= refs
    names = {item['name'] for item in components}
    assert {'Eclipse Temurin','nuaforma-server'} <= names
    assert not {'bubblewrap','nuaforma-renderer-seccomp','LibreOffice'} & names
    assert any(item.get('group') == 'org.verapdf' for item in components)
    assert any(item.get('purl','').startswith('pkg:deb/') for item in components)
    assert ('nuaforma-playground' in names) == (application == 'playground')
    assert not names & {'junit-jupiter','mockk','archunit','cyclonedx-gradle-plugin'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', required=True)
    parser.add_argument('--architecture', choices=('amd64','arm64'), required=True)
    parser.add_argument('--application', choices=('server','playground'), required=True)
    parser.add_argument('--expected-version', help='Require this exact packaged application and SBOM version')
    parser.add_argument('--docker', default='docker')
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    fonts = args.output / 'mixed-fonts'
    fonts.mkdir()
    shutil.copyfile('nuaforma-docx/src/test/resources/fonts/LibreBarcode39-Regular.ttf', fonts / 'surviving.ttf')
    (fonts / 'bad.ttf').write_bytes(b'not-a-font')
    runtime = Runtime(args, '--mount', f'type=bind,source={fonts.resolve()},target=/opt/nuaforma/custom-fonts,readonly')
    try:
        base = runtime.connect()
        status, _, catalog = http(base, '/api/v1/capabilities')
        providers = {provider['id']: provider for provider in json.loads(catalog)['providers']}
        assert status == 200
        assert [font['family'] for font in providers['nuaforma-docx']['fonts']['entries'] if font['source'] == 'mounted'] == ['Libre Barcode 39']
        assert any(font['source'] == 'bundled' for font in providers['nuaforma-docx']['fonts']['entries'])
        logs = runtime.command('logs', runtime.name)
        assert 'MOUNTED_FONT_REJECTED' in logs and 'bad.ttf' in logs
        assert ('MOUNTED_FONT_REJECTION_DETAIL' in logs) == (args.application == 'playground')
        if args.application == 'playground':
            assert 'OpenTypeMetadata' in logs and 'stack=' in logs and 'exception=' in logs
        status, headers, data = http(base, '/api/v1/sbom')
        assert status == 200
        assert headers.get('Content-Type','').startswith('application/vnd.cyclonedx+json')
        assert headers.get('Cache-Control') == 'no-store'
        assert headers.get('ETag') == '"' + hashlib.sha256(data).hexdigest() + '"'
        bom = json.loads(data)
        if args.expected_version:
            assert bom['metadata']['component']['version'] == args.expected_version
            status, _, version = http(base, '/api/v1/version')
            version = json.loads(version)
            assert status == 200 and version['version'] == args.expected_version
            assert version['contractVersion'] == '0.6'
        verify_bom(bom, args.architecture, args.application)
        (args.output / 'sbom.json').write_bytes(data)
        qualify_template_links(runtime, base, args.output)
        status, headers, body = http(base, '/playground/')
        if args.application == 'server':
            assert status == 404
            files = runtime.command('exec',runtime.name,'/bin/sh','-c',"find /opt/nuaforma/application/lib -name '*playground*' -print")
            assert not files
        else:
            assert status == 200 and b'DEVELOPMENT MODE' in body and b'field values may appear' in body
            assert "frame-ancestors 'none'" in headers.get('Content-Security-Policy','')
            for path in ('/playground/app.js','/playground/app.css','/playground/icon.png'):
                assert http(base,path)[0] == 200
            status, _, caps = http(base,'/api/v1/capabilities')
            assert status == 200 and any('pdf' in p['available']['writeFormats'] for p in json.loads(caps)['providers'])
            assert generate(base,'docx', template(), {'name':'Playground'})[0] == 200
            assert generate(base,'pdf', template(), {'name':'Playground'})[0] == 200
            logs = runtime.command('logs',runtime.name)
            assert 'DEVELOPMENT PLAYGROUND' in logs and 'NOT FOR PRODUCTION' in logs
        runtime.command('exec',runtime.name,'/opt/nuaforma/bin/healthcheck')
    finally:
        runtime.close()
    if args.application == 'playground':
        disabled = Runtime(args,'--env','NUAFORMA_PLAYGROUND=false')
        try:
            assert http(disabled.connect(),'/playground/')[0] == 404
        finally:
            disabled.close()
        invalid = Runtime(args,'--env','NUAFORMA_MODE=production')
        try:
            assert int(invalid.command('wait',invalid.name)) != 0
        finally:
            invalid.close()
    qualify_pdfa_policy(args)
    print('Shipped inventory and ' + args.application + ' boundary: PASS')


if __name__ == '__main__':
    main()
