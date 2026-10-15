"""Stage a new release site and seven-scenario image kit without changing the live-site sources."""
import argparse
import base64
import io
import json
import pathlib
import re
import shutil
import build_example_kit as kit
import build_client_collections as clients
import package_complete_kit as complete
from package_deployment_kits import package as package_deployments
from PIL import Image, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parents[2]


def prepare(destination, version):
    if not re.fullmatch(r'0\.2\.0-alpha\.1', version):
        raise ValueError('Only the selected 0.2.0-alpha.1 candidate is prepared')
    destination = destination.resolve()
    if not destination.is_relative_to(ROOT / 'build') or destination.exists():
        raise ValueError('Use a fresh ignored build directory, never the live-site source tree')
    shutil.copytree(ROOT / 'site', destination)
    downloads = destination / 'downloads'
    kit.OUT = clients.OUT = complete.OUT = downloads
    kit.build_template(include_content_image=True)
    kit.build_requests()
    for file in downloads.glob('[0-9][0-9]-*.json'):
        envelope = json.loads(file.read_text(encoding='utf-8'))
        envelope['data']['image'] = None
        file.write_text(json.dumps(envelope, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    picture = Image.new('RGB', (720, 240), '#eff5f4')
    draw = ImageDraw.Draw(picture)
    draw.rounded_rectangle((12, 12, 708, 228), radius=28, fill='#007e83')
    draw.text((40, 83), 'IMAGE FROM JSON', fill='white', font_size=52)
    image_bytes = io.BytesIO()
    picture.save(image_bytes, format='PNG')
    envelope = json.loads((downloads / '01-final-pdfa3b.json').read_text())
    envelope['data'].update(reference='NF-007', image=base64.b64encode(image_bytes.getvalue()).decode('ascii'))
    (downloads / '07-base64-image-pdfa3b.json').write_text(json.dumps(envelope, indent=2) + '\n', encoding='utf-8')
    scenarios = json.loads((downloads / 'scenarios.json').read_text())
    scenarios.append(dict(file='07-base64-image-pdfa3b.json', title='Base64 image from data',
        profile='PDF/A-3b · image content pipe', description='A real Base64 PNG in data.image is inserted by image(60, 25, true).',
        expected='A teal IMAGE FROM JSON illustration, 60 mm wide and 20 mm high, preserving its 3:1 ratio. Other scenarios supply null and add no image.'))
    (downloads / 'scenarios.json').write_text(json.dumps(scenarios, indent=2) + '\n', encoding='utf-8')
    for file in destination.rglob('*'):
        if not file.is_file() or not (file.suffix in ('.md', '.html', '.json', '.yaml', '.js') or file.name == 'Dockerfile'):
            continue
        text = file.read_text(encoding='utf-8')
        text = text.replace('0.1.0-alpha.3', version).replace('0.1.0-alpha.1', version)
        text = text.replace('all six', 'all seven').replace('All six', 'All seven').replace('six JSON', 'seven JSON').replace('six complete', 'seven complete').replace('six generation', 'seven generation').replace('six document', 'seven document').replace('11 requests', '12 requests')
        file.write_text(text, encoding='utf-8', newline='\n')
    # Client metadata is generated from the seven actual scenarios, not text substitutions.
    clients.build(version=version)
    examples = downloads / 'EXAMPLES.md'
    examples.write_text(examples.read_text(encoding='utf-8') + '\n## Base64 image scenario\n\n`07-base64-image-pdfa3b.json` supplies an opaque PNG in `data.image`. The template uses `{image | image(60, 25, true)}`: maximum width/height are millimetres and `true` preserves the aspect ratio. Null inserts no content; malformed images fail. No image file upload or network URL is needed. This 3:1 image produces a 60 by 20 mm drawing.\n', encoding='utf-8')
    # Do not silently reuse the alpha.3 Helm image digest for a new release.
    values = downloads / 'helm/nuaforma/values.yaml'
    text = values.read_text()
    if re.search(r'sha256:[a-f0-9]{64}', text):
        text = re.sub(r'sha256:[a-f0-9]{64}', 'REQUIRED_APPROVED_RELEASE_DIGEST', text)
        values.write_text(text, encoding='utf-8')
    package_deployments(downloads)
    kit.package()
    complete.package()
    (destination / 'RELEASE-PREPARATION.txt').write_text('NOT FOR PUBLICATION: verify candidate examples, update source/release explanations and endpoint lists, add Playground deployment kit, bind the exact approved Helm image digest, and verify public artifacts before publishing.\n', encoding='utf-8')
    print('Staged candidate examples and site; publication remains blocked pending final synchronization')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=pathlib.Path)
    parser.add_argument('--version', default='0.2.0-alpha.1')
    args = parser.parse_args()
    prepare(args.output, args.version)
