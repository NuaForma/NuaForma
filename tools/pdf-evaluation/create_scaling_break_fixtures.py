"""Build fresh inert controls for horizontal text scaling and explicit page breaks."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
from create_tab_collision_fixtures import parts as base_parts, W
from create_paragraph_formatting_fixtures import paragraph
from create_text_background_fixtures import run, shading


def scaling():
    body = ''
    for i, scale in enumerate([50, 80, 100, 125, 200]):
        body += paragraph(run(f'H{i:02}_START ') + run('MMMMiiii AVATAR 012345', f'<w:w w:val="{scale}"/>') + run(f' H{i:02}_END'))
    for i, scale in enumerate([80, 125]):
        body += paragraph(run(f'K{i:02}_START ') + run('AVATAR To WA 12345', f'<w:w w:val="{scale}"/><w:kern w:val="2"/>') + run(f' K{i:02}_END'))
        body += paragraph(run(f'C{i:02}_START ') + run('tracking ABC 123', f'<w:w w:val="{scale}"/><w:spacing w:val="20"/>') + run(f' C{i:02}_END'))
    body += paragraph(run('D00_LEFT ') + run('DECORATION TEXT', '<w:w w:val="80"/><w:u w:val="single"/><w:strike/>'+shading('FFFF00')) + run(' D00_END'))
    body += paragraph(run('W00_BEGIN '+('word wrap '*24)+'W00_END', '<w:w w:val="80"/>'))
    body += paragraph(run('W01_BEGIN '+('word wrap '*24)+'W01_END', '<w:w w:val="125"/>'))
    body += paragraph(run('S00_INHERITED'), '<w:pStyle w:val="Scaled"/>')
    body += paragraph(run('S01_RESET', '<w:w w:val="100"/>'), '<w:pStyle w:val="Scaled"/>')
    return body


def breaks(kind):
    br = '<w:r><w:rPr><w:sz w:val="20"/></w:rPr><w:br w:type="page"/></w:r>'
    if kind == 'middle':
        return paragraph(run('M00_BEFORE') + br + run('M01_AFTER')) + paragraph(run('M02_NEXT'))
    if kind == 'start':
        return paragraph(br + run('B00_AFTER')) + paragraph(run('B01_NEXT'))
    if kind == 'end':
        return paragraph(run('E00_BEFORE') + br) + paragraph(run('E01_NEXT'))
    if kind == 'consecutive':
        return paragraph(run('R00_BEFORE') + br + br + run('R01_AFTER'))
    if kind == 'empty':
        return paragraph(br) + paragraph(run('Q00_NEXT'))
    if kind == 'decorated':
        return paragraph(run('P00_FIRST ' + 'wrapping '*18) + br + run('P01_SECOND ' + 'wrapping '*18), shading('DDEEFF')+'<w:ind w:left="360" w:firstLine="360"/>', before=120, after=120)
    raise ValueError(kind)


def parts(paper, variant):
    result = base_parts(paper, '')
    body = scaling() if variant == 'scaling' else breaks(variant)
    section = result['word/document.xml'].split('<w:sectPr>')[1].split('</w:sectPr>')[0]
    result['word/document.xml'] = f'<w:document xmlns:w="{W}"><w:body>{body}<w:sectPr>{section}</w:sectPr></w:body></w:document>'
    result['word/styles.xml'] = result['word/styles.xml'].replace('</w:styles>', '<w:style w:type="paragraph" w:styleId="Scaled"><w:name w:val="Scaled"/><w:basedOn w:val="Normal"/><w:rPr><w:w w:val="80"/></w:rPr></w:style></w:styles>')
    return result


def build(output):
    output.mkdir(parents=True, exist_ok=False)
    for variant in ['scaling', 'middle', 'start', 'end', 'consecutive', 'empty', 'decorated']:
        for paper in ['a4', 'letter']:
            folder = output/variant/paper
            folder.mkdir(parents=True)
            with ZipFile(folder/'source.docx', 'x') as archive:
                for name, value in sorted(parts(paper, variant).items()):
                    info = ZipInfo(name, (2026, 11, 18, 0, 0, 0))
                    info.create_system = 0
                    info.compress_type = ZIP_DEFLATED
                    archive.writestr(info, value.encode())


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    build(parser.parse_args().output)
