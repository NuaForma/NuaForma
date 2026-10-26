"""Deterministic real fields with deliberately stale caches for independent Word qualification."""
import argparse
from pathlib import Path
from xml.sax.saxutils import escape
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
from build_fixture import build as base_entries, paragraph, run

KINDS = ['a4', 'letter', 'complex', 'wrapped']


def build(root, output, kind):
    entries = base_entries(root, output, entries_only=True)
    complex_fields = kind == 'complex'

    def field(code, cache='999', properties='', bookmark=False):
        result = run(cache, properties)
        if bookmark: result = '<w:bookmarkStart w:id="2" w:name="_Number"/>' + result + '<w:bookmarkEnd w:id="2"/>'
        if complex_fields:
            return ('<w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText xml:space="preserve"> '
                    + escape(code) + ' </w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r>'
                    + result + '<w:r><w:fldChar w:fldCharType="end"/></w:r>')
        return '<w:fldSimple w:instr="' + escape(code, {'"': '&quot;'}) + '">' + run(cache, properties) + '</w:fldSimple>'

    props = '<w:spacing w:before="0" w:after="120" w:line="320" w:lineRule="exact"/><w:widowControl/>'
    toc_code = r'TOC \o "1-2" \h \z \u'
    blocks = paragraph(run('Inhoudsopgave'), '<w:pStyle w:val="Title"/>')
    marker_props = '<w:spacing w:before="0" w:after="0" w:line="320" w:lineRule="exact"/>'
    if complex_fields:
        # A multi-paragraph cache must disappear as one field, including stale nested PAGEREF.
        cache = field(toc_code, 'OUTDATED CONTENTS')
        prefix, suffix = cache.rsplit('<w:r><w:fldChar w:fldCharType="end"/></w:r>', 1)
        blocks += paragraph(prefix, marker_props)
        blocks += paragraph(field(r'PAGEREF _Number \h'), marker_props)
        blocks += paragraph('<w:r><w:fldChar w:fldCharType="end"/></w:r>', marker_props)
    else:
        blocks += paragraph(field(toc_code, 'OUTDATED CONTENTS'), marker_props)
    long_title = ' — ' + 'Overzicht van contracten, voorwaarden en financiële afspraken voor internationale organisaties ' * 2 if kind == 'wrapped' else ''
    for chapter in range(1, 3):
        heading = field('SEQ Chapters', str(99 + chapter), '<w:b/>') + run(f' Hoofdstuk {chapter}' + long_title)
        blocks += paragraph(heading, '<w:pStyle w:val="Heading1"/><w:pageBreakBefore/>')
        reference = r'REF _Caption \h' if complex_fields else r'REF _Number \h'
        blocks += paragraph(run('Verwijzing vooruit naar figuur ') + field(reference)
                            + run(' op pagina ') + field(r'PAGEREF _Caption \h') + run('.'), props)
        blocks += paragraph(run('Details — Сведения και στοιχεία'), '<w:pStyle w:val="Heading2"/>')
        blocks += paragraph(run('Figuur ') + ('<w:bookmarkStart w:id="1" w:name="_Caption"/>' if chapter == 1 else '')
                            + ('<w:bookmarkStart w:id="2" w:name="_Number"/>' if chapter == 1 and not complex_fields else '')
                            + field(r'SEQ Figures \s 1', bookmark=chapter == 1 and complex_fields)
                            + ('<w:bookmarkEnd w:id="2"/>' if chapter == 1 and not complex_fields else '')
                            + run(' — overzicht van de aanvraag') + ('<w:bookmarkEnd w:id="1"/>' if chapter == 1 else ''), '<w:pStyle w:val="Caption"/>')
        blocks += paragraph(run('Op deze pagina: ') + field('PAGE') + run(' van ') + field('NUMPAGES')
                            + run('; deze sectie: ') + field('SECTIONPAGES') + run(' pagina’s.'), props)
        blocks += paragraph(run('Actueel volgnummer: ') + field(r'SEQ Figures \c') + run('.'), props)
        blocks += paragraph(run('Een toelichting met ') + run('vetgedrukte tekst', '<w:b/>')
                            + run(' en ') + run('cursieve tekst', '<w:i/>') + run('.'), props)
        blocks += paragraph(run('De verwijzingen blijven werken wanneer de inhoudsopgave groeit en paginanummers verschuiven. ' * 3), props)
    section = '<w:sectPr>' + entries['word/document.xml'].split('<w:sectPr>', 1)[1].split('</w:sectPr>', 1)[0] + '</w:sectPr>'
    if kind == 'letter': section = section.replace('w:w="11906" w:h="16838"', 'w:w="12240" w:h="15840"')
    entries['word/document.xml'] = entries['word/document.xml'].split('<w:body>', 1)[0] + '<w:body>' + blocks + section + '</w:body></w:document>'
    style_xml = ''
    for level in range(1, 3):
        style_xml += f'<w:style w:type="paragraph" w:styleId="Heading{level}"><w:name w:val="heading {level}"/><w:basedOn w:val="Normal"/><w:pPr><w:keepNext/><w:outlineLvl w:val="{level-1}"/><w:spacing w:before="120" w:after="120" w:line="360" w:lineRule="exact"/></w:pPr><w:rPr><w:b/></w:rPr></w:style>'
        style_xml += f'<w:style w:type="paragraph" w:styleId="TOC{level}"><w:name w:val="toc {level}"/><w:basedOn w:val="Normal"/><w:pPr><w:ind w:left="{(level-1)*360}"/><w:spacing w:before="0" w:after="40" w:line="320" w:lineRule="exact"/></w:pPr></w:style>'
    style_xml += '<w:style w:type="paragraph" w:styleId="Caption"><w:name w:val="caption"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:before="0" w:after="120" w:line="320" w:lineRule="exact"/></w:pPr><w:rPr><w:i/></w:rPr></w:style>'
    entries['word/styles.xml'] = entries['word/styles.xml'].replace('</w:styles>', style_xml + '</w:styles>')
    output.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(output, 'w') as archive:
        for name, value in sorted(entries.items()):
            info = ZipInfo(name, date_time=(2026, 10, 26, 0, 0, 0)); info.create_system = 0; info.compress_type = ZIP_DEFLATED
            archive.writestr(info, value.encode('utf-8') if isinstance(value, str) else value)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('output', type=Path)
    args = parser.parse_args(); root = Path(__file__).resolve().parents[3]
    for kind in KINDS: build(root, args.output / kind / 'source.docx', kind)
