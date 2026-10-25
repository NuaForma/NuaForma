"""Build one report with resolved A4/Letter-landscape/A3 next-page content ranges."""
import argparse
from copy import deepcopy
from pathlib import Path
from xml.etree import ElementTree as ET
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
from build_report_fixture import build as report
from build_fixture import paragraph, run

W='http://schemas.openxmlformats.org/wordprocessingml/2006/main'
for prefix,uri in [('w',W),('r','http://schemas.openxmlformats.org/officeDocument/2006/relationships'),('wp','http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing'),('a','http://schemas.openxmlformats.org/drawingml/2006/main'),('pic','http://schemas.openxmlformats.org/drawingml/2006/picture')]:ET.register_namespace(prefix,uri)
Q='{'+W+'}'

def build(root, output):
    entries=report(root,output,entries_only=True)
    document=ET.fromstring(entries['word/document.xml']);body=document.find(Q+'body')
    original=list(body);master=original[-1];body.clear()
    for i,(label,width,height,orientation) in enumerate([('A A4 staand',11906,16838,'portrait'),('B Letter liggend',15840,12240,'landscape'),('C A3 staand',16838,23811,'portrait')]):
        content=deepcopy(original[:-1]);content[0].find('.//'+Q+'t').text='Deel '+label
        # Avoid reproducing the separate Letter proof's known one-word wrap in this scope trial.
        for node in content:
            for text in node.iter(Q+'t'):
                if text.text:text.text=text.text.replace('Nieuwe opmerkingen worden meegenomen in de volgende beoordelingsronde, met behoud van de overeengekomen werkwijze.','De volgende beoordelingsronde verwerkt de opmerkingen volgens de afgesproken werkwijze.')
        localized=ET.fromstring(paragraph(run('Київ і Україна. ', '<w:lang w:val="uk-UA"/>')+run('Έκθεση έργου.', '<w:lang w:val="el-GR"/>')).replace('<w:p>',f'<w:p xmlns:w="{W}">',1))
        content.insert(1,localized)
        geometry=deepcopy(master)
        size=geometry.find(Q+'pgSz');size.set(Q+'w',str(width));size.set(Q+'h',str(height));size.set(Q+'orient',orientation)
        geometry.insert(2,ET.Element(Q+'type',{Q+'val':'nextPage'}))
        # Resolve link-to-previous inheritance in the adapter; only the first range names the bands.
        if i:
            for tag in ['headerReference','footerReference']:
                node=geometry.find(Q+tag)
                if node is not None:geometry.remove(node)
        end=ET.fromstring(paragraph(run('Einde deel '+label)).replace('<w:p>',f'<w:p xmlns:w="{W}">',1))
        content.append(end)
        if i<2:
            properties=end.find(Q+'pPr')
            if properties is None:properties=ET.Element(Q+'pPr');end.insert(0,properties)
            properties.append(geometry)
        body.extend(content)
        if i==2:body.append(geometry)
    entries['word/document.xml']=ET.tostring(document,encoding='utf-8',xml_declaration=True)
    output.parent.mkdir(parents=True,exist_ok=True)
    with ZipFile(output,'w') as z:
        for name,value in sorted(entries.items()):
            info=ZipInfo(name,date_time=(2026,10,25,0,0,0));info.create_system=0;info.compress_type=ZIP_DEFLATED
            z.writestr(info,value.encode('utf-8') if isinstance(value,str) else value)

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('output',type=Path);a=p.parse_args();build(Path(__file__).resolve().parents[3],a.output)
