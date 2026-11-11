"""Reproduce the synthetic Word paragraph-default source packages for #288.

Run from the repository root with a NEW output directory. Export each source
through Microsoft Word without updating fields; preserve the raw export receipt.
No private corpus document is used or copied.
"""
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
import argparse
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    out = parser.parse_args().output
    out.mkdir(parents=True, exist_ok=False)
    r = Path('.')
    word = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
    ET.register_namespace('w', word)

    def q(name):
        return '{' + word + '}' + name

    for paper in ['a4', 'letter']:
        with ZipFile(r / 'nuaforma-docx-pdf/src/test/resources/preferred-table-widths' / paper / 'source.docx') as z:
            base = {n: z.read(n) for n in z.namelist()}
        for kind in ['explicit-normal', 'implicit-normal', 'implicit-normal-reordered', 'explicit-doc-defaults', 'implicit-no-normal', 'implicit-no-normal-reordered', 'explicit-custom']:
            parts = base.copy()
            styles = ET.fromstring(parts['word/styles.xml'])
            normal = styles.find(q('style'))
            assert normal.get(q('styleId')) == 'Normal'
            normal.append(ET.fromstring(f'<w:pPr xmlns:w="{word}"><w:spacing w:before="60" w:after="80" w:line="400" w:lineRule="exact"/></w:pPr>'))
            normal.append(ET.fromstring(f'<w:rPr xmlns:w="{word}"><w:b/><w:color w:val="AA0000"/><w:sz w:val="26"/></w:rPr>'))
            custom = ET.fromstring(f'<w:style xmlns:w="{word}" w:type="paragraph" w:styleId="CustomRoot"><w:name w:val="Custom Root"/><w:pPr><w:spacing w:before="100" w:after="120" w:line="440" w:lineRule="exact"/><w:ind w:left="400"/></w:pPr><w:rPr><w:i/><w:color w:val="0000AA"/><w:sz w:val="28"/></w:rPr></w:style>')
            styles.append(custom)
            if kind != 'explicit-normal' and kind != 'explicit-doc-defaults':
                normal.attrib.pop(q('default'))
            if kind == 'explicit-doc-defaults':
                for node in list(normal):
                    if node.tag in [q('pPr'), q('rPr')]:
                        normal.remove(node)
            if 'no-normal' in kind:
                styles.remove(normal)
            if kind.endswith('reordered'):
                styles.remove(custom)
                styles.insert(1, custom)
                if 'no-normal' in kind:
                    secondary = ET.fromstring(f'<w:style xmlns:w="{word}" w:type="paragraph" w:styleId="Secondary"><w:name w:val="Secondary"/><w:pPr><w:spacing w:line="480" w:lineRule="exact"/></w:pPr><w:rPr><w:color w:val="008800"/><w:sz w:val="32"/></w:rPr></w:style>')
                    styles.insert(1, secondary)
            if kind == 'explicit-custom':
                custom.set(q('default'), '1')
            parts['word/styles.xml'] = ET.tostring(styles, encoding='utf-8')
            if 'word/stylesWithEffects.xml' in parts:
                parts['word/stylesWithEffects.xml'] = parts['word/styles.xml']
            document = ET.fromstring(parts['word/document.xml'])
            body = document.find(q('body'))
            section = body.find(q('sectPr'))
            for child in list(body):
                body.remove(child)
            for marker, style in [('P00_IMPLICIT', ''), ('P01_IMPLICIT', ''), ('P02_CUSTOM', 'CustomRoot')]:
                p = ET.SubElement(body, q('p'))
                props = ET.SubElement(p, q('pPr'))
                if style:
                    ET.SubElement(props, q('pStyle'), {q('val'): style})
                run = ET.SubElement(p, q('r'))
                ET.SubElement(run, q('t')).text = marker + ' sample text'
            body.append(section)
            parts['word/document.xml'] = ET.tostring(document, encoding='utf-8')
            folder = out / 'body' / kind / paper
            folder.mkdir(parents=True, exist_ok=False)
            with ZipFile(folder / 'source.docx', 'x', ZIP_DEFLATED) as z:
                for name, data in parts.items():
                    entry = ZipInfo(name, (2025, 1, 1, 0, 0, 0))
                    entry.create_system = 0
                    entry.compress_type = ZIP_DEFLATED
                    z.writestr(entry, data)
    for paper in ['a4', 'letter']:
        with ZipFile(r / 'build/288-default-style-probes/implicit-normal' / paper / 'source.docx') as z:
            base = {n: z.read(n) for n in z.namelist()}
        for kind in ['implicit-normal-disabled', 'implicit-alias-name-normal', 'implicit-lowercase-id', 'implicit-lowercase-name']:
            parts = base.copy()
            styles = ET.fromstring(parts['word/styles.xml'])
            normal = next((n for n in styles.findall(q('style')) if n.get(q('styleId')) == 'Normal'))
            if kind == 'implicit-normal-disabled':
                normal.set(q('default'), '0')
            elif kind == 'implicit-lowercase-id':
                normal.set(q('styleId'), 'normal')
            else:
                normal.set(q('styleId'), 'RenamedStyle')
                if kind == 'implicit-lowercase-name':
                    normal.find(q('name')).set(q('val'), 'normal')
            parts['word/styles.xml'] = ET.tostring(styles, encoding='utf-8')
            if 'word/stylesWithEffects.xml' in parts:
                parts['word/stylesWithEffects.xml'] = parts['word/styles.xml']
            dest = out / 'body' / kind / paper
            dest.mkdir(parents=True, exist_ok=False)
            with ZipFile(dest / 'source.docx', 'x', ZIP_DEFLATED) as z:
                for name, data in parts.items():
                    entry = ZipInfo(name, (2025, 1, 1, 0, 0, 0))
                    entry.create_system = 0
                    entry.compress_type = ZIP_DEFLATED
                    z.writestr(entry, data)
    for paper in ['a4', 'letter']:
        with ZipFile(r / 'build/288-default-style-probes/implicit-normal' / paper / 'source.docx') as z:
            base = {n: z.read(n) for n in z.namelist()}
        for kind in ['implicit-id-normal-other-name', 'implicit-name-normal-over-id', 'explicit-name-normal-over-id', 'implicit-name-normal-whitespace']:
            parts = base.copy()
            styles = ET.fromstring(parts['word/styles.xml'])
            normal = next((n for n in styles.findall(q('style')) if n.get(q('styleId')) == 'Normal'))
            if kind == 'implicit-name-normal-whitespace':
                normal.set(q('styleId'), 'RenamedStyle')
                normal.find(q('name')).set(q('val'), ' Normal ')
            else:
                normal.find(q('name')).set(q('val'), 'Other Name')
            if kind.endswith('over-id'):
                chosen = ET.fromstring(f'<w:style xmlns:w="{word}" w:type="paragraph" w:styleId="NameChosen"><w:name w:val="Normal"/><w:pPr><w:spacing w:before="80" w:after="40" w:line="480" w:lineRule="exact"/></w:pPr><w:rPr><w:color w:val="008800"/><w:sz w:val="32"/></w:rPr></w:style>')
                if kind.startswith('explicit'):
                    chosen.set(q('default'), '1')
                styles.append(chosen)
            parts['word/styles.xml'] = ET.tostring(styles, encoding='utf-8')
            if 'word/stylesWithEffects.xml' in parts:
                parts['word/stylesWithEffects.xml'] = parts['word/styles.xml']
            dest = out / 'body' / kind / paper
            dest.mkdir(parents=True, exist_ok=False)
            with ZipFile(dest / 'source.docx', 'x', ZIP_DEFLATED) as z:
                for name, data in parts.items():
                    entry = ZipInfo(name, (2025, 1, 1, 0, 0, 0))
                    entry.create_system = 0
                    entry.compress_type = ZIP_DEFLATED
                    z.writestr(entry, data)
    for paper in ['a4', 'letter']:
        for kind in ['explicit-normal', 'explicit-doc-defaults', 'implicit-normal', 'implicit-no-normal']:
            with ZipFile(out / 'body' / kind / paper / 'source.docx') as z:
                parts = {n: z.read(n) for n in z.namelist()}
            settings = ET.fromstring(parts['word/settings.xml'])
            compat = settings.find(q('compat'))
            if compat is None:
                compat = ET.SubElement(settings, q('compat'))
            nodes = [n for n in compat.findall(q('compatSetting')) if n.get(q('name')) == 'compatibilityMode']
            if nodes:
                nodes[0].set(q('val'), '15')
            else:
                ET.SubElement(compat, q('compatSetting'), {q('name'): 'compatibilityMode', q('uri'): 'http://schemas.microsoft.com/office/word', q('val'): '15'})
            parts['word/settings.xml'] = ET.tostring(settings, encoding='utf-8')
            dest = out / 'body' / ('15-' + kind) / paper
            dest.mkdir(parents=True, exist_ok=False)
            with ZipFile(dest / 'source.docx', 'x', ZIP_DEFLATED) as z:
                for name, data in parts.items():
                    entry = ZipInfo(name, (2025, 1, 1, 0, 0, 0))
                    entry.create_system = 0
                    entry.compress_type = ZIP_DEFLATED
                    z.writestr(entry, data)
    for paper in ['a4', 'letter']:
        with ZipFile(r / 'nuaforma-docx-pdf/src/test/resources/table-style-defaults' / paper / 'source.docx') as z:
            base = {n: z.read(n) for n in z.namelist()}
        for mode in ['12']:
            for kind in ['explicit-normal', 'implicit-normal', 'implicit-doc-defaults']:
                parts = base.copy()
                styles = ET.fromstring(parts['word/styles.xml'])
                normal = next((n for n in styles.findall(q('style')) if n.get(q('styleId')) == 'Normal'))
                if kind == 'implicit-normal':
                    normal.attrib.pop(q('default'))
                if kind == 'implicit-doc-defaults':
                    styles.remove(normal)
                table = next((n for n in styles.findall(q('style')) if n.get(q('styleId')) == 'NativeDefaultTable'))
                table.append(ET.fromstring(f'<w:pPr xmlns:w="{word}"><w:jc w:val="center"/></w:pPr>'))
                table.append(ET.fromstring(f'<w:rPr xmlns:w="{word}"><w:sz w:val="20"/></w:rPr>'))
                parts['word/styles.xml'] = ET.tostring(styles, encoding='utf-8')
                if 'word/stylesWithEffects.xml' in parts:
                    parts['word/stylesWithEffects.xml'] = parts['word/styles.xml']
                document = ET.fromstring(parts['word/document.xml'])
                for run in document.iter(q('r')):
                    for props in list(run):
                        if props.tag == q('rPr'):
                            run.remove(props)
                parts['word/document.xml'] = ET.tostring(document, encoding='utf-8')
                settings = ET.fromstring(parts['word/settings.xml'])
                compat = settings.find(q('compat'))
                if compat is None:
                    compat = ET.SubElement(settings, q('compat'))
                nodes = [n for n in compat.findall(q('compatSetting')) if n.get(q('name')) == 'compatibilityMode']
                if nodes:
                    nodes[0].set(q('val'), mode)
                else:
                    ET.SubElement(compat, q('compatSetting'), {q('name'): 'compatibilityMode', q('uri'): 'http://schemas.microsoft.com/office/word', q('val'): mode})
                parts['word/settings.xml'] = ET.tostring(settings, encoding='utf-8')
                folder = out / 'table' / (mode + '-' + kind) / paper
                folder.mkdir(parents=True, exist_ok=False)
                with ZipFile(folder / 'source.docx', 'x', ZIP_DEFLATED) as z:
                    for name, data in parts.items():
                        entry = ZipInfo(name, (2025, 1, 1, 0, 0, 0))
                        entry.create_system = 0
                        entry.compress_type = ZIP_DEFLATED
                        z.writestr(entry, data)
    for paper in ['a4', 'letter']:
        with ZipFile(out / 'body/implicit-normal' / paper / 'source.docx') as z:
            original = {name: z.read(name) for name in z.namelist()}
        for kind, spacing in [('nbsp', '\u00a0'), ('en-space', '\u2002'), ('tab', '\t'), ('wide', '\u3000'), ('narrow-nbsp', '\u202f')]:
            parts = original.copy()
            styles = ET.fromstring(parts['word/styles.xml'])
            normal = next(node for node in styles.findall(q('style')) if node.get(q('styleId')) == 'Normal')
            normal.find(q('name')).set(q('val'), spacing + 'Normal' + spacing)
            parts['word/styles.xml'] = ET.tostring(styles, encoding='utf-8')
            if 'word/stylesWithEffects.xml' in parts:
                parts['word/stylesWithEffects.xml'] = parts['word/styles.xml']
            target = out / 'body' / ('name-whitespace-' + kind) / paper
            target.mkdir(parents=True, exist_ok=False)
            with ZipFile(target / 'source.docx', 'x', ZIP_DEFLATED) as archive:
                for name, content in parts.items():
                    entry = ZipInfo(name, (2025, 1, 1, 0, 0, 0))
                    entry.create_system = 0
                    entry.compress_type = ZIP_DEFLATED
                    archive.writestr(entry, content)
    print('54 synthetic source packages created; Word PDF export remains a separate step.')
if __name__ == '__main__':
    main()
