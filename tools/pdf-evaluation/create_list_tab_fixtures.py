"""Create bounded numbered-list inline-tab controls for independent Word comparison."""
import argparse
from pathlib import Path
import xml.etree.ElementTree as E
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

root = Path(__file__).resolve().parents[2]


def build(out):
    out.mkdir(parents=True, exist_ok=False)
    W='http://schemas.openxmlformats.org/wordprocessingml/2006/main';Q='{'+W+'}'
    R='http://schemas.openxmlformats.org/package/2006/relationships';CT='http://schemas.openxmlformats.org/package/2006/content-types'
    E.register_namespace('w',W)
    for paper in ['a4','letter']:
        with ZipFile(root/f'nuaforma-docx-pdf/src/test/resources/unwrapped-extents/frame-control/{paper}/source.docx') as z: original={n:z.read(n) for n in z.namelist()}
        for mode in ['default-left','explicit-left','right','center','decimal','collision','wrapped','soft-lines']:
            parts=original.copy();doc=E.fromstring(parts['word/document.xml']);body=doc.find(Q+'body');section=body.find(Q+'sectPr');body[:]=[]
            numbering=E.fromstring(f'<w:numbering xmlns:w="{W}"><w:abstractNum w:abstractNumId="0"><w:multiLevelType w:val="singleLevel"/><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/><w:lvlJc w:val="left"/><w:pPr><w:tabs><w:tab w:val="num" w:pos="720"/></w:tabs><w:ind w:left="720" w:hanging="360"/></w:pPr><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="24"/></w:rPr></w:lvl></w:abstractNum><w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num></w:numbering>')
            for index in range(2):
                p=E.SubElement(body,Q+'p');pr=E.SubElement(p,Q+'pPr')
                num=E.SubElement(pr,Q+'numPr');E.SubElement(num,Q+'ilvl',{Q+'val':'0'});E.SubElement(num,Q+'numId',{Q+'val':'1'})
                if mode!='default-left':
                    tabs=E.SubElement(pr,Q+'tabs');E.SubElement(tabs,Q+'tab',{Q+'val':'num',Q+'pos':'720'})
                    align=mode if mode in ['right','center','decimal'] else 'left'
                    E.SubElement(tabs,Q+'tab',{Q+'val':align,Q+'pos':'3600' if mode!='collision' else '1440'})
                E.SubElement(pr,Q+'spacing',{Q+'before':'0',Q+'after':'120',Q+'line':'300',Q+'lineRule':'auto'})

                def run(text=None,control=None):
                    r=E.SubElement(p,Q+'r');rp=E.SubElement(r,Q+'rPr');E.SubElement(rp,Q+'rFonts',{Q+'ascii':'Liberation Sans',Q+'hAnsi':'Liberation Sans'});E.SubElement(rp,Q+'sz',{Q+'val':'24'})
                    if text is not None:E.SubElement(r,Q+'t',{'{http://www.w3.org/XML/1998/namespace}space':'preserve'}).text=text
                    if control:E.SubElement(r,Q+control)

                prefix=f'L{index}0_START'
                if mode=='collision':prefix+=' LONG PREFIX INSIDE A NUMBERED PARAGRAPH'
                run(prefix);run(control='tab');run(f'L{index}1_12,50_END' if mode=='decimal' else f'L{index}1_TAIL')
                if mode=='wrapped':run(' Word controls preserve numbering, inline tab stops and the continuation indent when this longer list item continues onto another text line. '*3)
                if mode=='soft-lines':run(control='br');run(f'L{index}2_NEXT');run(control='tab');run(f'L{index}3_FINAL')
            body.append(section)
            parts['word/document.xml']=E.tostring(doc,encoding='utf-8');parts['word/numbering.xml']=E.tostring(numbering,encoding='utf-8')
            rels=E.fromstring(parts['word/_rels/document.xml.rels']);E.SubElement(rels,'{'+R+'}Relationship',{'Id':'listTabNumbering','Type':'http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering','Target':'numbering.xml'});parts['word/_rels/document.xml.rels']=E.tostring(rels,encoding='utf-8')
            types=E.fromstring(parts['[Content_Types].xml']);E.SubElement(types,'{'+CT+'}Override',{'PartName':'/word/numbering.xml','ContentType':'application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml'});parts['[Content_Types].xml']=E.tostring(types,encoding='utf-8')
            target=out/mode/paper/'source.docx';target.parent.mkdir(parents=True)
            with ZipFile(target,'w',ZIP_DEFLATED) as z:
                for name,data in parts.items():
                    info=ZipInfo(name,(2026,11,18,0,0,0));info.create_system=0;info.compress_type=ZIP_DEFLATED;z.writestr(info,data)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    build(parser.parse_args().output)
