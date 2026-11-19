"""Create fixed and automatically sized Word table alignment controls."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED, ZIP_STORED
import xml.etree.ElementTree as E

root = Path(__file__).resolve().parents[2]


def fixed(out):
    out.mkdir(parents=True, exist_ok=False)
    W='http://schemas.openxmlformats.org/wordprocessingml/2006/main';Q='{'+W+'}';E.register_namespace('w',W)
    for paper in ['a4','letter']:
        with ZipFile(root/f'nuaforma-docx-pdf/src/test/resources/unwrapped-extents/frame-control/{paper}/source.docx') as z:original={n:z.read(n) for n in z.namelist()}
        for compatibility in ['12','15']:
            for align in ['omitted','left','center','right']:
                parts=original.copy();doc=E.fromstring(parts['word/document.xml']);body=doc.find(Q+'body');section=body.find(Q+'sectPr');body[:]=[]

                def paragraph(parent,text):
                    p=E.SubElement(parent,Q+'p');pr=E.SubElement(p,Q+'pPr');E.SubElement(pr,Q+'spacing',{Q+'before':'0',Q+'after':'0',Q+'line':'240',Q+'lineRule':'auto'})
                    r=E.SubElement(p,Q+'r');rp=E.SubElement(r,Q+'rPr');E.SubElement(rp,Q+'rFonts',{Q+'ascii':'Liberation Sans',Q+'hAnsi':'Liberation Sans'});E.SubElement(rp,Q+'sz',{Q+'val':'24'});E.SubElement(r,Q+'t').text=text

                paragraph(body,'A00_BODY')
                table=E.SubElement(body,Q+'tbl');pr=E.SubElement(table,Q+'tblPr');E.SubElement(pr,Q+'tblW',{Q+'w':'4200',Q+'type':'dxa'})
                if align!='omitted':E.SubElement(pr,Q+'jc',{Q+'val':align})
                E.SubElement(pr,Q+'tblInd',{Q+'w':'240',Q+'type':'dxa'})
                borders=E.SubElement(pr,Q+'tblBorders')
                for side in ['top','left','bottom','right','insideH','insideV']:E.SubElement(borders,Q+side,{Q+'val':'single',Q+'sz':'4',Q+'color':'008B90'})
                E.SubElement(pr,Q+'tblLayout',{Q+'type':'fixed'});margins=E.SubElement(pr,Q+'tblCellMar')
                for side in ['top','left','bottom','right']:E.SubElement(margins,Q+side,{Q+'w':'80',Q+'type':'dxa'})
                grid=E.SubElement(table,Q+'tblGrid')
                for width in ['2400','1800']:E.SubElement(grid,Q+'gridCol',{Q+'w':width})
                for row in range(3):
                    tr=E.SubElement(table,Q+'tr')
                    for col,width in enumerate(['2400','1800']):
                        cell=E.SubElement(tr,Q+'tc');cp=E.SubElement(cell,Q+'tcPr');E.SubElement(cp,Q+'tcW',{Q+'w':width,Q+'type':'dxa'});paragraph(cell,f'T{row}{col}_CELL')
                paragraph(body,'A99_NEXT');body.append(section);parts['word/document.xml']=E.tostring(doc,encoding='utf-8')
                settings=E.fromstring(parts['word/settings.xml']);compat=E.SubElement(settings,Q+'compat');E.SubElement(compat,Q+'compatSetting',{Q+'name':'compatibilityMode',Q+'uri':'http://schemas.microsoft.com/office/word',Q+'val':compatibility});parts['word/settings.xml']=E.tostring(settings,encoding='utf-8')
                target=out/f'mode{compatibility}-{align}'/paper/'source.docx';target.parent.mkdir(parents=True)
                with ZipFile(target,'w',ZIP_DEFLATED) as z:
                    for name,data in parts.items():
                        info=ZipInfo(name,(2026,11,18,0,0,0));info.create_system=0;info.compress_type=ZIP_DEFLATED;z.writestr(info,data)


def automatic(out):
    out.mkdir(parents=True, exist_ok=False)
    word = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
    E.register_namespace('w', word[1:-1])
    for paper in ['a4', 'letter']:
        with ZipFile(root / f'nuaforma-docx-pdf/src/test/resources/preferred-table-widths/{paper}/source.docx') as archive:
            original = {name: archive.read(name) for name in archive.namelist()}
        for alignment in ['omitted', 'left', 'center', 'right']:
            parts = original.copy()
            document = E.fromstring(parts['word/document.xml'])
            for properties in document.iter(word + 'tblPr'):
                for child in properties.findall(word + 'jc'):
                    properties.remove(child)
                if alignment != 'omitted':
                    E.SubElement(properties, word + 'jc', {word + 'val': alignment})
            parts['word/document.xml'] = E.tostring(document, encoding='utf-8')
            settings = E.fromstring(parts['word/settings.xml'])
            compatibility = settings.find(word + 'compat')
            if compatibility is None:
                compatibility = E.SubElement(settings, word + 'compat')
            E.SubElement(compatibility, word + 'compatSetting', {
                word + 'name': 'compatibilityMode', word + 'uri': 'http://schemas.microsoft.com/office/word', word + 'val': '15'})
            parts['word/settings.xml'] = E.tostring(settings, encoding='utf-8')
            target = out / alignment / paper / 'source.docx'
            target.parent.mkdir(parents=True)
            with ZipFile(target, 'w', ZIP_STORED) as archive:
                for name, data in parts.items():
                    entry = ZipInfo(name, (2026, 11, 19, 0, 0, 0))
                    entry.create_system = 0
                    entry.compress_type = ZIP_STORED
                    archive.writestr(entry, data)


def build(output):
    output.mkdir(parents=True, exist_ok=False)
    fixed(output / 'fixed')
    automatic(output / 'auto')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    build(parser.parse_args().output)
