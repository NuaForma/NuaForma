"""Build a mixed-format report with more than nine pages and stale PAGE/NUMPAGES caches."""
import argparse
from copy import deepcopy
from pathlib import Path
from xml.etree import ElementTree as ET
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
from build_page_master_fixtures import build as masters
from build_sections_fixture import W,Q
from build_fixture import run


def build(root,output):
    entries=masters(root,output,entries_only=True)
    document=ET.fromstring(entries['word/document.xml'])
    tables=document.find(Q+'body').findall(Q+'tbl')
    rows=tables[-1].findall(Q+'tr')
    assert len(tables)==3 and len(rows)==29
    for number in range(29,141):
        row=deepcopy(rows[1+(number-1)%28])
        row.find(Q+'tc').find('.//'+Q+'t').text=f'W{number:03d}'
        tables[-1].append(row)
    entries['word/document.xml']=ET.tostring(document,encoding='utf-8',xml_declaration=True)
    for name in [f'word/{kind}{number}.xml' for kind in ['header','footer'] for number in [1,2,3]]:
        story=ET.fromstring(entries[name])
        paragraph=list(story.iter(Q+'p'))[-1]
        def fragment(xml):return ET.fromstring(f'<w:p xmlns:w="{W}">{xml}</w:p>')[0]
        def text(value):return fragment(run(value,'<w:sz w:val="18"/>'))
        size=22 if '/header' in name else 18
        def field(instruction):return fragment(f'<w:fldSimple w:instr=" {instruction} ">'+run('999',f'<w:sz w:val="{size}"/>')+'</w:fldSimple>')
        if '/header' in name:
            paragraph.extend([text(' | Pagina '),field('PAGE')])
        else:
            for cached in paragraph.iter(Q+'fldSimple'):
                for node in cached.iter(Q+'t'):node.text='999'
        paragraph.extend([text(' van '),field('NUMPAGES')])
        entries[name]=ET.tostring(story,encoding='utf-8',xml_declaration=True)
    output.parent.mkdir(parents=True,exist_ok=True)
    with ZipFile(output,'w') as archive:
        for name,value in sorted(entries.items()):
            info=ZipInfo(name,date_time=(2026,10,25,0,0,0));info.create_system=0;info.compress_type=ZIP_DEFLATED
            archive.writestr(info,value.encode('utf-8') if isinstance(value,str) else value)

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('output',type=Path);a=p.parse_args()
    build(Path(__file__).resolve().parents[3],a.output)
