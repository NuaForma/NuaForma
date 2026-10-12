"""Research artifact notices and an OSV snapshot; not a security guarantee."""
import json
import pathlib
import urllib.request
import zipfile

root = pathlib.Path(__file__).parent
coordinates = [line.split('=')[0] for line in (root / 'gradle.lockfile').read_text().splitlines()
               if line and not line.startswith('#') and line != 'empty=annotationProcessor']
queries = [dict(package=dict(ecosystem='Maven', name=':'.join(coordinate.split(':')[:2])),
                version=coordinate.split(':')[2]) for coordinate in coordinates]
request = urllib.request.Request('https://api.osv.dev/v1/querybatch',
                                 data=json.dumps(dict(queries=queries)).encode(),
                                 headers={'Content-Type': 'application/json'})
with urllib.request.urlopen(request, timeout=60) as response:
    result = json.load(response)
(root / 'build' / 'osv.json').write_text(json.dumps(dict(coordinates=coordinates, result=result), indent=2))
print('OSV coordinates:', len(coordinates))
for coordinate, findings in zip(coordinates, result['results']):
    if findings.get('vulns'):
        print(coordinate, findings)
for jar in sorted((root / 'build/install/nuaforma-pdf-evaluation/lib').glob('*fonts*.jar')):
    print('\nFONT ARTIFACT', jar.name)
    with zipfile.ZipFile(jar) as package:
        for name in package.namelist():
            if any(word in name.lower() for word in ('license', 'ofl', 'notice', 'copying')):
                print(name, package.read(name).decode('utf-8', errors='replace')[:450])
