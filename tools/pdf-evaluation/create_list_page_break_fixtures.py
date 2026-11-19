from pathlib import Path
from zipfile import ZipFile,ZipInfo,ZIP_DEFLATED
import xml.etree.ElementTree as E
root=Path(__file__).resolve().parents[2]
import argparse
parser=argparse.ArgumentParser(description="Create numbered-paragraph page-break controls")
parser.add_argument("output",type=Path)
out=parser.parse_args().output
out.mkdir(parents=True,exist_ok=False)
W='http://schemas.openxmlformats.org/wordprocessingml/2006/main';Q='{'+W+'}'
E.register_namespace('w',W)
for paper in ['a4','letter']:
    source=root/f'nuaforma-docx-pdf/src/test/resources/list-tabs/default-left/{paper}/source.docx'
    with ZipFile(source) as archive:original={name:archive.read(name) for name in archive.namelist()}
    for mode in ['middle','leading','trailing','consecutive','empty-first','empty-last']:
        parts=original.copy();doc=E.fromstring(parts['word/document.xml'])
        body=doc.find(Q+'body')
        for index,p in enumerate(body.findall(Q+'p')):
            if mode.startswith('empty-'):
                if index==(0 if mode=='empty-first' else 1):
                    p[:]=[p.find(Q+'pPr')];r=E.SubElement(p,Q+'r');E.SubElement(r,Q+'br',{Q+'type':'page'})
                continue
            if mode in ['middle','consecutive']:
                for r in p.findall(Q+'r'):
                    for tab in list(r.findall(Q+'tab')):
                        tab.tag=Q+'br';tab.set(Q+'type','page')
                        if mode=='consecutive':E.SubElement(r,Q+'br',{Q+'type':'page'})
            else:
                r=E.Element(Q+'r');E.SubElement(r,Q+'br',{Q+'type':'page'})
                if mode=='leading':p.insert(1,r)
                else:p.append(r)
        if mode.startswith('empty-'):
            end=E.Element(Q+'p');run=E.SubElement(end,Q+'r');E.SubElement(run,Q+'t').text='END_LIST_PAGE_BREAK';body.insert(len(body)-1,end)
        parts['word/document.xml']=E.tostring(doc,encoding='utf-8')
        target=out/mode/paper/'source.docx';target.parent.mkdir(parents=True)
        with ZipFile(target,'w',ZIP_DEFLATED) as archive:
            for name,data in parts.items():
                info=ZipInfo(name,(2026,11,19,0,0,0));info.create_system=0;info.compress_type=ZIP_DEFLATED;archive.writestr(info,data)
