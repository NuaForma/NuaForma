"""Build first/odd/even page-band profiles, including explicit blank variants and inheritance."""
import argparse
from pathlib import Path
from xml.etree import ElementTree as ET
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
from build_sections_fixture import build as sections, Q, W

R='http://schemas.openxmlformats.org/officeDocument/2006/relationships'

def build(root,output,blank_initial=False):
    entries=sections(root,output,entries_only=True)
    original_header=entries['word/header1.xml'];original_footer=entries['word/footer1.xml']
    for n,label in [(1,'ODD'),(2,'FIRST'),(3,'EVEN')]:
        entries[f'word/header{n}.xml']=original_header.replace('Voortgangsrapport','H-'+label).replace('<wp:docPr id="1"',f'<wp:docPr id="{10+n}"')
        entries[f'word/footer{n}.xml']=original_footer.replace('NF-2026-0057','F-'+label)
        if n>1:
            entries[f'word/_rels/header{n}.xml.rels']=entries['word/_rels/header1.xml.rels']
            entries['word/_rels/document.xml.rels']=entries['word/_rels/document.xml.rels'].replace('</Relationships>',f'<Relationship Id="header{n}" Type="{R}/header" Target="header{n}.xml"/><Relationship Id="footer{n}" Type="{R}/footer" Target="footer{n}.xml"/></Relationships>')
            entries['[Content_Types].xml']=entries['[Content_Types].xml'].replace('</Types>',f'<Override PartName="/word/header{n}.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/><Override PartName="/word/footer{n}.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/></Types>')
    assert 'word/settings.xml' not in entries
    entries['word/settings.xml']=f'<w:settings xmlns:w="{W}"><w:evenAndOddHeaders/></w:settings>'
    entries['word/_rels/document.xml.rels']=entries['word/_rels/document.xml.rels'].replace('</Relationships>',f'<Relationship Id="settings" Type="{R}/settings" Target="settings.xml"/></Relationships>')
    entries['[Content_Types].xml']=entries['[Content_Types].xml'].replace('</Types>','<Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/></Types>')
    document=ET.fromstring(entries['word/document.xml']);geometry=list(document.iter(Q+'sectPr'))
    assert len(geometry)==3
    for i,node in enumerate(geometry):
        # The complete profile inherits all three stories into ranges B and C.
        # The blank profile starts without first/even stories, defines them at B and disables titlePg at C.
        if i==(1 if blank_initial else 0):
            for n,kind in [(2,'first'),(3,'even')]:
                for name in ['header','footer']:
                    node.insert(0,ET.Element(Q+name+'Reference',{Q+'type':kind,'{'+R+'}id':name+str(n)}))
        node.append(ET.Element(Q+'titlePg',{Q+'val':'false' if blank_initial and i==2 else 'true'}))
        order={Q+tag:n for n,tag in enumerate(['headerReference','footerReference','type','pgSz','pgMar','titlePg'])}
        node[:]=sorted(node,key=lambda child:order[child.tag])
    entries['word/document.xml']=ET.tostring(document,encoding='utf-8',xml_declaration=True)
    output.parent.mkdir(parents=True,exist_ok=True)
    with ZipFile(output,'w') as archive:
        for name,value in sorted(entries.items()):
            info=ZipInfo(name,date_time=(2026,10,25,0,0,0));info.create_system=0;info.compress_type=ZIP_DEFLATED
            archive.writestr(info,value.encode('utf-8') if isinstance(value,str) else value)

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('output',type=Path);a=p.parse_args();root=Path(__file__).resolve().parents[3]
    for name in ['variants','blank']:build(root,a.output/name/'source.docx',name=='blank')
