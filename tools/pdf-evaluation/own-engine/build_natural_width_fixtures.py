"""Natural text widths with repeated headers on A4 and US Letter."""
import argparse,re
from pathlib import Path
from zipfile import ZipFile,ZipInfo,ZIP_DEFLATED
from build_advanced_fixtures import cell,row,table,header
from build_report_fixture import build as report_entries
from build_fixture import paragraph,run

def text(value,properties='',align='left'):
 return paragraph(run(value,'<w:sz w:val="18"/>'+properties),f'<w:spacing w:before="0" w:after="0" w:line="240" w:lineRule="exact"/><w:jc w:val="{align}"/>')

def build(root,output,kind):
 entries=report_entries(root,output,entries_only=True)
 original=entries['word/document.xml'];prefix=original.split('<w:body>')[0]
 section='<w:sectPr>'+original.split('<w:sectPr>',1)[1].split('</w:sectPr>',1)[0]+'</w:sectPr>'
 if kind=='letter':section=section.replace('w:w="11906" w:h="16838"','w:w="12240" w:h="15840"')
 widths=[3200,3200,3238];rows=[header(widths,['Code','Onderdeel','Bedrag'])]
 labels=['Controle afspraak','Kwaliteitscontrole','Afspraak bevestigd','Контроль даних','Έλεγχος εγγράφων']
 for i in range(1,41):
  label='Documenttransformatieonderzoek' if i==40 else labels[(i-1)%len(labels)]
  properties='<w:b/>' if i%5==2 else '<w:i/>' if i%5==3 else ''
  description=text(label,properties)
  if i==1:description=paragraph(run('Controle ','<w:sz w:val="18"/>')+run('afspraak','<w:sz w:val="18"/><w:b/>'),'<w:spacing w:before="0" w:after="0" w:line="240" w:lineRule="exact"/>')
  amount=f'€ {i*125:,},00'.replace(',','.',1) if i*125>=1000 else f'€ {i*125},00'
  rows.append(row([cell(text(f'W{i:02d}'),widths[0],fill='EAF3F4' if i%2 else None),cell(description,widths[1]),cell(text(amount,'<w:color w:val="008B90"/>','right'),widths[2])]))
 content=table(widths,rows).replace('w:type="fixed"','w:type="autofit"')
 content=re.sub(r'<w:tblW\b[^>]*/>','<w:tblW w:w="0" w:type="auto"/>',content)
 content=re.sub(r'<w:tcW\b[^>]*/>','<w:tcW w:w="0" w:type="auto"/>',content)
 borders='<w:tcBorders>'+''.join(f'<w:{side} w:val="single" w:sz="4" w:color="B8C5CC"/>' for side in ['top','left','bottom','right'])+'</w:tcBorders>'
 content,count=re.subn(r'<w:tcW\b[^>]*/>',lambda match:match.group(0)+borders,content)
 assert count==123,'Unexpected cell inventory'
 blocks=paragraph(run('Controleoverzicht met automatische kolombreedtes'),'<w:pStyle w:val="Title"/>')+paragraph(run('Kolombreedtes volgen de breedste tekst, met behoud van letterstijl en celmarges.'))+content+paragraph(run('Einde van het controleoverzicht.'))
 entries['word/document.xml']=prefix+'<w:body>'+blocks+section+'</w:body></w:document>'
 output.parent.mkdir(parents=True,exist_ok=True)
 with ZipFile(output,'w') as archive:
  for name,value in sorted(entries.items()):
   info=ZipInfo(name,date_time=(2026,10,25,0,0,0));info.create_system=0;info.compress_type=ZIP_DEFLATED
   archive.writestr(info,value.encode('utf-8') if isinstance(value,str) else value)

if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('output',type=Path)
 args=parser.parse_args();root=Path(__file__).resolve().parents[3]
 for kind in ['a4','letter']:build(root,args.output/kind/'source.docx',kind)
