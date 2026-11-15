"""Generate fresh synthetic soft-line DOCX sources; never overwrite references or golden files."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED, ZipInfo
import argparse
import copy
import xml.etree.ElementTree as E

W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
REL = 'http://schemas.openxmlformats.org/package/2006/relationships'
CT = 'http://schemas.openxmlformats.org/package/2006/content-types'
OFFICE = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
E.register_namespace('w', W)


def q(name):
    return '{' + W + '}' + name


def run(text='',size=12,control=''):
    return f'<w:r><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="{size*2}"/></w:rPr>'+ (f'<w:t xml:space="preserve">{text}</w:t>' if text else '')+control+'</w:r>'


def p(content,align='left',extra='',automatic=False,mark=12):
    return f'<w:p><w:pPr><w:spacing w:before="0" w:after="0" w:line="240" w:lineRule="'+('auto' if automatic else 'exact')+f'"/><w:jc w:val="{align}"/>'+extra+f'<w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="{mark*2}"/></w:rPr></w:pPr>'+content+'</w:p>'


def cell(content):
    return '<w:tc><w:tcPr><w:tcW w:w="2500" w:type="dxa"/></w:tcPr>'+p(content)+'</w:tc>'


def table():
    borders=''.join(f'<w:{edge} w:val="single" w:sz="4" w:color="000000"/>' for edge in ['top','left','bottom','right','insideH','insideV'])
    margins=''.join(f'<w:{edge} w:w="80" w:type="dxa"/>' for edge in ['top','left','bottom','right'])
    return '<w:tbl><w:tblPr><w:tblW w:w="5000" w:type="dxa"/><w:tblLayout w:type="fixed"/><w:tblBorders>'+borders+'</w:tblBorders><w:tblCellMar>'+margins+'</w:tblCellMar></w:tblPr><w:tblGrid><w:gridCol w:w="2500"/><w:gridCol w:w="2500"/></w:tblGrid><w:tr>'+cell(run('L60_CELL',control='<w:br/>')+run('L61_NEXT'))+cell(run('L62_OTHER'))+'</w:tr><w:tr>'+cell(run('L63_ROW'))+cell(run('L64_LAST',control='<w:br/>')+run('L65_END'))+'</w:tr></w:tbl>'


def rtl(text,br=False):
    return '<w:r><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans" w:cs="Liberation Sans"/><w:sz w:val="24"/><w:szCs w:val="24"/><w:bCs w:val="0"/><w:iCs w:val="0"/><w:rtl w:val="1"/><w:lang w:val="he-IL"/></w:rPr><w:t>'+text+'</w:t>'+('<w:br/>' if br else '')+'</w:r>'


def build(out):
    r = Path(__file__).resolve().parents[2]
    if out.exists():
        raise FileExistsError('Use a fresh output directory; existing sources and goldens are protected')
    out.mkdir(parents=True)
    cases = {
     'ordinary':p(run('L00_BEFORE'))+p(run('L01_ALPHA',control='<w:br/>')+run('L02_BETA'))+
        p(run(control='<w:br/>')+run('L03_LEAD',control='<w:br/><w:br/>')+run('L04_GAP',control='<w:br/>'))+p(run('L05_AFTER')),
     'alignment':p(run('L10_RIGHT',control='<w:br/>')+run('L11_RIGHTNEXT'),'right')+
        p(run('L12_CENTER',control='<w:br/>')+run('L13_CENTERNEXT'),'center')+
        p(run('L14_JUST aa bb cc',control='<w:br/>')+run('L15_LAST dd ee'),'both')+
        p(run('L16_HANG',control='<w:br/>')+run('L17_CONTINUE'),extra='<w:ind w:left="720" w:hanging="360"/>'),
     'mixed':p(run('L20_BEFORE'),automatic=True)+p(run('L21_SMALL',10)+run(size=20,control='<w:br/>')+run('L22_NEXT',10),automatic=True)+
        p(run('L23_TRAIL',10)+run(size=20,control='<w:br/>'),automatic=True)+p(run('L24_AFTER'),automatic=True),
     'carriage':p(run('L30_FIRST',control='<w:cr/>')+run('L31_SECOND',control='<w:cr/><w:cr/>')+run('L32_THIRD')),
     'tabs':p(run('L40_BEFORE'))+p(run(control='<w:tab/>')+run('L41_TAB',control='<w:br/>')+run('L42_NEXT'),extra='<w:tabs><w:tab w:val="right" w:pos="2160"/></w:tabs>')+p(run('L43_AFTER'))
    }
    cases['mark-metrics'] = p(run('L50_BEFORE'),automatic=True)+p(run(size=6,control='<w:br/>')+run('L51_SMALL',10,control='<w:br/>')+
        run(size=20,control='<w:br/>')+run('L52_NEXT',10),automatic=True)+p(run('L53_TRAIL',10,control='<w:br/>'),automatic=True,mark=20)+p(run('L54_AFTER'),automatic=True)
    for paper in ['a4','letter']:
        with ZipFile(r/'nuaforma-docx-pdf/src/test/resources/preferred-table-widths'/paper/'source.docx') as archive:
            original={name:archive.read(name) for name in archive.namelist()}
        for kind,contents in cases.items():
            parts=dict(original);document=E.fromstring(parts['word/document.xml']);body=document.find(q('body'));section=body.find(q('sectPr'))
            for node in list(body):body.remove(node)
            content=E.fromstring('<w:body xmlns:w="'+W+'">'+contents+'</w:body>');body.extend(content);body.append(section)
            parts['word/document.xml']=E.tostring(document,encoding='utf-8')
            folder=out/kind/paper;folder.mkdir(parents=True,exist_ok=False)
            with ZipFile(folder/'source.docx','x',ZIP_DEFLATED) as archive:
                for name,data in parts.items():archive.writestr(ZipInfo(name, (2025, 1, 1, 0, 0, 0)), data)
    for paper in ['a4','letter']:
        with ZipFile(r/'nuaforma-docx-pdf/src/test/resources/preferred-table-widths/letter/source.docx') as archive:
            original={name:archive.read(name) for name in archive.namelist()}
        for kind in ['table-bands','pagination','list','rtl']:
            parts=dict(original);document=E.fromstring(parts['word/document.xml']);body=document.find(q('body'));section=body.find(q('sectPr'))
            if paper=='a4':section.find(q('pgSz')).set(q('w'),'11906');section.find(q('pgSz')).set(q('h'),'16838')
            if kind=='table-bands':
                contents=p(run('L59_BEFORE'))+table()+p(run('L66_AFTER'))
                relations=E.fromstring(parts['word/_rels/document.xml.rels'])
                for relationship in relations:
                    if relationship.get('Type','').endswith(('/header','/footer')):
                        header=relationship.get('Type').endswith('/header');part='word/'+relationship.get('Target')
                        tag='hdr' if header else 'ftr';first='L67_HEAD' if header else 'L69_FOOT';second='L68_HEADNEXT' if header else 'L70_FOOTNEXT'
                        parts[part]=(f'<w:{tag} xmlns:w="{W}">'+p(run(first,control='<w:br/>')+run(second))+f'</w:{tag}>').encode()
            elif kind=='pagination':
                available=int(section.find(q('pgSz')).get(q('h')))-2880
                before=p(run('L71_BEFORE')).replace('w:line="240"',f'w:line="{available-720}"')
                contents=before+p(run('L72_ONE',control='<w:br/>')+run('L73_TWO',control='<w:br/>')+run('L74_THREE',control='<w:br/>')+run('L75_FOUR'),extra='<w:widowControl w:val="0"/><w:keepLines w:val="0"/>')+p(run('L76_AFTER'))
            elif kind=='list':
                number='<w:numPr><w:ilvl w:val="0"/><w:numId w:val="7"/></w:numPr>'
                contents=p(run('L80_ITEM',control='<w:br/>')+run('L81_CONTINUE'),extra=number)+p(run('L82_NEXT'),extra=number)
                parts['word/numbering.xml']=(f'<w:numbering xmlns:w="{W}"><w:abstractNum w:abstractNumId="7"><w:multiLevelType w:val="singleLevel"/><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/><w:lvlJc w:val="left"/><w:suff w:val="space"/><w:pPr><w:ind w:left="720" w:hanging="360"/></w:pPr><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="24"/></w:rPr></w:lvl></w:abstractNum><w:num w:numId="7"><w:abstractNumId w:val="7"/></w:num></w:numbering>').encode()
                relationships=E.fromstring(parts['word/_rels/document.xml.rels']);E.SubElement(relationships,'{'+REL+'}Relationship',{'Id':'line-numbering','Type':OFFICE+'/numbering','Target':'numbering.xml'})
                parts['word/_rels/document.xml.rels']=E.tostring(relationships,encoding='utf-8')
                types=E.fromstring(parts['[Content_Types].xml']);E.SubElement(types,'{'+CT+'}Override',{'PartName':'/word/numbering.xml','ContentType':'application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml'})
                parts['[Content_Types].xml']=E.tostring(types,encoding='utf-8')
            else:
                contents=p(rtl('אבג',True)+rtl('דהו'),align='right',extra='<w:bidi w:val="1"/>')+p(rtl('זחט'),align='right',extra='<w:bidi w:val="1"/>')
            for node in list(body):body.remove(node)
            content=E.fromstring('<w:body xmlns:w="'+W+'">'+contents+'</w:body>');body.extend(content);body.append(section)
            parts['word/document.xml']=E.tostring(document,encoding='utf-8')
            folder=out/kind/paper;folder.mkdir(parents=True,exist_ok=False)
            with ZipFile(folder/'source.docx','x',ZIP_DEFLATED) as archive:
                for name,data in parts.items():archive.writestr(ZipInfo(name, (2025, 1, 1, 0, 0, 0)), data)
    for paper in ['a4','letter']:
        source=out/'rtl'/paper/'source.docx'
        with ZipFile(source) as archive:parts={name:archive.read(name) for name in archive.namelist()}
        document=E.fromstring(parts['word/document.xml']);body=document.find(q('body'));first,third=body.findall(q('p'));section=body.find(q('sectPr'))
        one=copy.deepcopy(first);two=copy.deepcopy(first)
        one.remove(one.findall(q('r'))[1]);one.find(q('r')).remove(one.find(q('r')).find(q('br')))
        two.remove(two.findall(q('r'))[0])
        for node in list(body):body.remove(node)
        body.extend([one,two,third,section]);parts['word/document.xml']=E.tostring(document,encoding='utf-8')
        folder=out/'paragraphs'/paper;folder.mkdir(parents=True,exist_ok=False)
        with ZipFile(folder/'source.docx','x',ZIP_DEFLATED) as archive:
            for name,data in parts.items():archive.writestr(ZipInfo(name, (2025, 1, 1, 0, 0, 0)), data)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    build(parser.parse_args().output)
