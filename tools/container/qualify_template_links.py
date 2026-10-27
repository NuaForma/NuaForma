"""Exercise passive template detachment through the actual production/Playground HTTP API."""
import io
import json
import zipfile
from qualify_service import generate, template, verify_pdf_profile


def attached_template(style=None):
    office = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
    with zipfile.ZipFile(io.BytesIO(template())) as archive:
        parts = {name: archive.read(name) for name in archive.namelist()}
    parts['[Content_Types].xml'] = parts['[Content_Types].xml'].replace(b'</Types>',
        b'<Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/></Types>')
    relationship = f'<Relationship Id="settings139" Type="{office}/settings" Target="settings.xml"/></Relationships>'
    parts['word/_rels/document.xml.rels'] = parts['word/_rels/document.xml.rels'].replace(b'</Relationships>', relationship.encode())
    updates = '' if style is None else f'<w:linkStyles w:val="{style}"/>'
    parts['word/settings.xml'] = (f'<w:settings xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="{office}">'
        f'<w:attachedTemplate r:id="template139"/>{updates}</w:settings>').encode()
    parts['word/_rels/settings.xml.rels'] = (f'<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        f'<Relationship Id="template139" Type="{office}/attachedTemplate" Target="http://127.0.0.1:65534/PRIVATE_TEMPLATE.dotm" TargetMode="External"/></Relationships>').encode()
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, data in parts.items():
            archive.writestr(name, data)
    return output.getvalue()


def qualify(runtime, base, output):
    records = []
    for style in (None, 'false', 'off', '0', 'true'):
        for format_id in ('docx', 'pdf'):
            status, _, data = generate(base, format_id, attached_template(style), {'name': 'Passive template'},
                                       {'profile': 'standard'} if format_id == 'pdf' else {})
            if style == 'true':
                assert status == 422, (format_id, style, status)
                diagnostics = json.loads(data)['diagnostics']
                assert any(item.get('code') == 'UNSUPPORTED_INPUT' for item in diagnostics), diagnostics
                if runtime.args.application == 'playground':
                    assert any(item.get('reason') == 'EXTERNAL_TEMPLATE_STYLE_UPDATES_NOT_SUPPORTED' for item in diagnostics), diagnostics
                else:
                    assert all('reason' not in item and 'remediation' not in item for item in diagnostics)
                assert b'PRIVATE_TEMPLATE' not in data
            else:
                assert status == 200, (format_id, style, status, data[:300])
                path = output / f'passive-template-{style or "absent"}.{format_id}'
                path.write_bytes(data)
                if format_id == 'docx':
                    with zipfile.ZipFile(io.BytesIO(data)) as archive:
                        assert b'Hello Passive template' in archive.read('word/document.xml')
                        assert b'attachedTemplate' not in archive.read('word/settings.xml')
                        assert all(b'PRIVATE_TEMPLATE' not in archive.read(name) for name in archive.namelist() if name.endswith('.rels'))
                else:
                    verify_pdf_profile(runtime, path, 'standard')
            records.append(dict(format=format_id, linkStyles=style, status=status))
    (output / 'passive-template-qualification.json').write_text(json.dumps(records, indent=2) + '\n', encoding='utf-8')
    logs = runtime.command('logs', runtime.name)
    assert 'EXTERNAL_TEMPLATE_STYLE_UPDATES_NOT_SUPPORTED' in logs
    assert 'PRIVATE_TEMPLATE' not in logs
