"""Build bounded section numbering, SECTIONPAGES and page-band parity experiments."""
import argparse
from pathlib import Path
from xml.etree import ElementTree as ET
from zipfile import ZipFile,ZipInfo,ZIP_DEFLATED
from build_page_master_fixtures import build as masters
from build_sections_fixture import W,Q
from build_fixture import run

PROFILES={
    'restart':([25,1,None],[True,False,False]),
    'offset':([0,100,None],[False,True,False]),
    'continue':([2,None,None],[False,True,False]),
    'shared':([25,1,None],[False,False,False]),
}

def build(root,output,profile):
    entries=masters(root,output,entries_only=True)
    if profile=='shared':entries['word/settings.xml']=entries['word/settings.xml'].replace('<w:evenAndOddHeaders/>','<w:evenAndOddHeaders w:val="false"/>')
    document=ET.fromstring(entries['word/document.xml'])
    starts,titles=PROFILES[profile]
    for index,section in enumerate(document.iter(Q+'sectPr')):
        if starts[index] is not None or profile=='restart' and index==2:
            attributes={Q+'fmt':'decimal'}
            if starts[index] is not None:attributes[Q+'start']=str(starts[index])
            section.append(ET.Element(Q+'pgNumType',attributes))
        section.find(Q+'titlePg').set(Q+'val','true' if titles[index] else 'false')
        order={Q+tag:n for n,tag in enumerate(['headerReference','footerReference','type','pgSz','pgMar','pgNumType','titlePg'])}
        section[:]=sorted(section,key=lambda child:order[child.tag])
    entries['word/document.xml']=ET.tostring(document,encoding='utf-8',xml_declaration=True)
    for name in [f'word/{kind}{n}.xml' for kind in ['header','footer'] for n in [1,2,3]]:
        story=ET.fromstring(entries[name]);paragraph=list(story.iter(Q+'p'))[-1]
        def fragment(value):return ET.fromstring(f'<w:p xmlns:w="{W}">{value}</w:p>')[0]
        def text(value):return fragment(run(value,'<w:sz w:val="18"/>'))
        def field(instruction):
            size=22 if '/header' in name else 18
            return fragment(f'<w:fldSimple w:instr=" {instruction} ">'+run('999',f'<w:sz w:val="{size}"/>')+'</w:fldSimple>')
        if '/header' in name:paragraph.extend([text(' | P '),field('PAGE')])
        else:
            for cached in paragraph.iter(Q+'fldSimple'):
                for node in cached.iter(Q+'t'):node.text='999'
        paragraph.extend([text(' / S '),field('SECTIONPAGES'),text(' / T '),field('NUMPAGES')])
        entries[name]=ET.tostring(story,encoding='utf-8',xml_declaration=True)
    output.parent.mkdir(parents=True,exist_ok=True)
    with ZipFile(output,'w') as archive:
        for name,value in sorted(entries.items()):
            info=ZipInfo(name,date_time=(2026,10,25,0,0,0));info.create_system=0;info.compress_type=ZIP_DEFLATED
            archive.writestr(info,value.encode('utf-8') if isinstance(value,str) else value)

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('output',type=Path);a=p.parse_args()
    for name in PROFILES:build(Path(__file__).resolve().parents[3],a.output/name/'source.docx',name)
