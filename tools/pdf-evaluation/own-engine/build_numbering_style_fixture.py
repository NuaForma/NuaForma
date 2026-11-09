"""Build numbering definition, inheritance and heading controls without overwriting references."""
import argparse
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

WORD = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
OFFICE = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'


def read(path):
    with ZipFile(path) as archive:
        return {name: archive.read(name) for name in archive.namelist()}


def write(parts, folder):
    folder.mkdir(parents=True, exist_ok=False)
    with ZipFile(folder / 'source.docx', 'x', ZIP_DEFLATED) as archive:
        for name, data in parts.items():
            entry = ZipInfo(name, (2025, 1, 1, 0, 0, 0))
            entry.create_system = 0
            entry.compress_type = ZIP_DEFLATED
            archive.writestr(entry, data)


def initial(base, output, paper):
    original = read(base / paper / 'source.docx')
    document = original['word/document.xml'].decode()
    prefix = document.split('<w:body>')[0] + '<w:body>'
    suffix = '<w:sectPr>' + document.split('<w:sectPr>')[1]
    variants = ['control0', 'control1', 'definition-link', 'inherited-unlinked-ilvl1', 'associated1-agreed',
                'associated1-mismatch', 'direct0-associated1', 'direct2-associated1']
    for variant in variants:
        parts = original.copy()
        associated = 'associated1' in variant
        declared = 1 if variant in ['inherited-unlinked-ilvl1', 'associated1-agreed'] else 0
        paragraph_style = variant not in ['control0', 'control1', 'definition-link']
        direct = 0 if variant in ['control0', 'definition-link', 'direct0-associated1'] else 1 if variant == 'control1' else 2 if variant == 'direct2-associated1' else None
        body = ''
        for index in range(3):
            properties = '<w:pStyle w:val="ProbeList"/>' if paragraph_style else ''
            if direct is not None:
                properties += f'<w:numPr><w:ilvl w:val="{direct}"/><w:numId w:val="1"/></w:numPr>'
            body += '<w:p><w:pPr>' + properties + '<w:spacing w:before="0" w:after="0" w:line="280" w:lineRule="exact"/><w:widowControl w:val="0"/></w:pPr><w:r><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="20"/></w:rPr><w:t>N0' + str(index) + ' Numbered probe</w:t></w:r></w:p>'
        parts['word/document.xml'] = (prefix + body + suffix).encode()
        numbering = f'<w:numbering xmlns:w="{WORD}"><w:abstractNum w:abstractNumId="0"><w:multiLevelType w:val="multilevel"/>'
        if variant == 'definition-link':
            numbering += '<w:styleLink w:val="ProbeNumbering"/>'
        for level in range(3):
            linked = '<w:pStyle w:val="ProbeList"/>' if associated and level == 1 else ''
            numbering += f'<w:lvl w:ilvl="{level}"><w:start w:val="1"/><w:numFmt w:val="decimal"/>' + linked + f'<w:lvlText w:val="%{level+1}."/><w:suff w:val="tab"/><w:lvlJc w:val="left"/><w:pPr><w:tabs><w:tab w:val="num" w:pos="{720*(level+1)}"/></w:tabs><w:ind w:left="{720*(level+1)}" w:hanging="360"/></w:pPr><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="20"/></w:rPr></w:lvl>'
        parts['word/numbering.xml'] = (numbering + '</w:abstractNum><w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num></w:numbering>').encode()
        styles = '<w:style w:type="numbering" w:styleId="ProbeNumbering"><w:name w:val="Synthetic numbering style"/><w:pPr><w:numPr><w:numId w:val="1"/></w:numPr></w:pPr><w:rPr><w:b/><w:color w:val="FF0000"/><w:rFonts w:ascii="Missing Numbering Style Font" w:hAnsi="Missing Numbering Style Font"/></w:rPr></w:style>'
        styles += f'<w:style w:type="paragraph" w:styleId="ProbeList"><w:name w:val="Synthetic paragraph list"/><w:pPr><w:numPr><w:ilvl w:val="{declared}"/><w:numId w:val="1"/></w:numPr></w:pPr></w:style>'
        for name in ['word/styles.xml', 'word/stylesWithEffects.xml']:
            if name in parts:
                parts[name] = parts[name].decode().replace('</w:styles>', styles + '</w:styles>').encode()
        name = 'word/_rels/document.xml.rels'
        parts[name] = parts[name].decode().replace('</Relationships>', f'<Relationship Id="syntheticNumbering" Type="{OFFICE}/numbering" Target="numbering.xml"/></Relationships>').encode()
        name = '[Content_Types].xml'
        parts[name] = parts[name].decode().replace('</Types>', '<Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/></Types>').encode()
        write(parts, output / variant / paper)


def inherited(output, paper):
    variants = {
        'control2': (False, None, None, None, '<w:ilvl w:val="2"/><w:numId w:val="1"/>'),
        'base1-derived2': (False, 1, 2, True, None),
        'base1-derived-absent': (False, 1, None, False, None),
        'base1-derived-id-only': (False, 1, None, True, None),
        'base1-derived-level-only': (False, 1, 2, False, None),
        'direct-id-only-style1': (False, None, 1, True, '<w:numId w:val="1"/>'),
        'direct-level-only-style1': (False, None, 1, True, '<w:ilvl w:val="2"/>'),
        'associated1-style-id-only': (True, None, None, True, None),
        'associated1-direct-id-style0': (True, None, 0, True, '<w:numId w:val="1"/>'),
    }
    for variant, (associated, base, level, has_id, direct) in variants.items():
        parts = read(output / ('associated1-agreed' if associated else 'control1') / paper / 'source.docx')
        document = re.sub(r'<w:pStyle[^>]*/>', '', parts['word/document.xml'].decode())
        document = re.sub(r'<w:numPr>.*?</w:numPr>', '', document)
        properties = '' if variant == 'control2' else '<w:pStyle w:val="ProbeList"/>'
        if direct is not None:
            properties += '<w:numPr>' + direct + '</w:numPr>'
        parts['word/document.xml'] = document.replace('<w:pPr>', '<w:pPr>' + properties).encode()
        for name in ['word/styles.xml', 'word/stylesWithEffects.xml']:
            if name not in parts:
                continue
            styles = re.sub(r'<w:style w:type="paragraph" w:styleId="ProbeList">.*?</w:style>', '', parts[name].decode())
            extra = ''
            if base is not None:
                extra += f'<w:style w:type="paragraph" w:styleId="BaseList"><w:name w:val="Base list"/><w:pPr><w:numPr><w:ilvl w:val="{base}"/><w:numId w:val="1"/></w:numPr></w:pPr></w:style>'
            number = (f'<w:ilvl w:val="{level}"/>' if level is not None else '') + ('<w:numId w:val="1"/>' if has_id else '')
            extra += '<w:style w:type="paragraph" w:styleId="ProbeList"><w:name w:val="Probe list"/>' + ('<w:basedOn w:val="BaseList"/>' if base is not None else '') + ('<w:pPr><w:numPr>' + number + '</w:numPr></w:pPr>' if number else '') + '</w:style>'
            parts[name] = styles.replace('</w:styles>', extra + '</w:styles>').encode()
        write(parts, output / variant / paper)


def associations(output, paper):
    variants = {
        'associated-no-style-numbering-direct-id': ('', '<w:numId w:val="1"/>'),
        'associated-no-numbering-anywhere': ('', None),
        'associated-style-level-only-direct-id': ('<w:ilvl w:val="1"/>', '<w:numId w:val="1"/>'),
        'associated-style-id-only-direct-level': ('<w:numId w:val="1"/>', '<w:ilvl w:val="2"/>'),
        'associated-style-level1-direct-disable': ('<w:ilvl w:val="1"/><w:numId w:val="1"/>', '<w:numId w:val="0"/>'),
        'associated-style-level1-direct-instance2': ('<w:ilvl w:val="1"/><w:numId w:val="1"/>', '<w:numId w:val="2"/>'),
    }
    for variant, (number, direct) in variants.items():
        parts = read(output / 'associated1-agreed' / paper / 'source.docx')
        document = parts['word/document.xml'].decode()
        if direct is not None:
            document = document.replace('<w:pStyle w:val="ProbeList"/>', '<w:pStyle w:val="ProbeList"/><w:numPr>' + direct + '</w:numPr>')
        parts['word/document.xml'] = document.encode()
        for name in ['word/styles.xml', 'word/stylesWithEffects.xml']:
            if name not in parts:
                continue
            pattern = r'(<w:style w:type="paragraph" w:styleId="ProbeList">.*?)<w:pPr>.*?</w:pPr>'
            text, count = re.subn(pattern, lambda match: match.group(1) + ('<w:pPr><w:numPr>' + number + '</w:numPr></w:pPr>' if number else ''), parts[name].decode())
            assert count == 1
            parts[name] = text.encode()
        parts['word/numbering.xml'] = parts['word/numbering.xml'].decode().replace('</w:numbering>', '<w:num w:numId="2"><w:abstractNumId w:val="0"/></w:num></w:numbering>').encode()
        write(parts, output / variant / paper)


def headings(output, paper):
    variants = [('custom-outline-absent', False, None, None), ('custom-outline-zero', False, 0, None),
                ('custom-outline-one', False, 1, None), ('heading-absent', True, None, None),
                ('heading-zero', True, 0, None), ('heading-one', True, 1, None), ('heading-direct-zero', True, 1, 0)]
    for variant, heading, level, direct in variants:
        parts = read(output / 'associated1-agreed' / paper / 'source.docx')
        identity = 'Heading2' if heading else 'ProbeList'
        for name in ['word/document.xml', 'word/numbering.xml', 'word/styles.xml', 'word/stylesWithEffects.xml']:
            if name not in parts:
                continue
            text = parts[name].decode().replace('ProbeList', 'Heading2') if heading else parts[name].decode()
            if name == 'word/document.xml' and direct is not None:
                text = text.replace(f'<w:pStyle w:val="{identity}"/>', f'<w:pStyle w:val="{identity}"/><w:numPr><w:ilvl w:val="{direct}"/></w:numPr>')
            if name in ['word/styles.xml', 'word/stylesWithEffects.xml']:
                pattern = f'<w:style w:type="paragraph" w:styleId="{identity}">.*?</w:style>'

                def modify(match):
                    style = match.group()
                    old = '<w:ilvl w:val="1"/>'
                    assert style.count(old) == 1
                    style = style.replace(old, '' if level is None else f'<w:ilvl w:val="{level}"/>')
                    style = style.replace('</w:pPr>', '<w:outlineLvl w:val="1"/></w:pPr>')
                    if heading:
                        style = style.replace('Synthetic paragraph list', 'heading 2')
                    return style.replace('</w:style>', '<w:rPr><w:b w:val="0"/><w:i w:val="0"/><w:color w:val="000000"/></w:rPr></w:style>')

                text, count = re.subn(pattern, modify, text)
                assert count == 1
            parts[name] = text.encode()
        write(parts, output / variant / paper)


def inert_links(output, paper):
    for variant, value in [('missing-style', 'Missing {literal'), ('non-numbering-style', 'Normal')]:
        parts = read(output / 'definition-link' / paper / 'source.docx')
        parts['word/numbering.xml'] = parts['word/numbering.xml'].decode().replace('<w:styleLink w:val="ProbeNumbering"/>', f'<w:styleLink w:val="{value}"/>').encode()
        write(parts, output / variant / paper)


def without_cache(output, paper):
    parts = read(output / 'inherited-unlinked-ilvl1' / paper / 'source.docx')
    assert parts.pop('word/stylesWithEffects.xml', None) is not None
    for name, attribute, value in [('word/_rels/document.xml.rels', 'Target', 'stylesWithEffects.xml'),
                                   ('[Content_Types].xml', 'PartName', '/word/stylesWithEffects.xml')]:
        tree = ET.fromstring(parts[name])
        matches = [node for node in tree if node.get(attribute) == value]
        assert len(matches) == 1
        tree.remove(matches[0])
        parts[name] = ET.tostring(tree, encoding='utf-8')
    write(parts, output / 'inherited-without-effects' / paper)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    for paper in ['a4', 'letter']:
        initial(args.base, args.output, paper)
        inherited(args.output, paper)
        associations(args.output, paper)
        headings(args.output, paper)
        inert_links(args.output, paper)
        without_cache(args.output, paper)
