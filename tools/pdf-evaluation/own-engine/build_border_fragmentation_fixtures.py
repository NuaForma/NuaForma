"""Explicitly splittable solid-border rows on A4 and US Letter."""
import argparse,re
from pathlib import Path
from zipfile import ZipFile,ZipInfo,ZIP_DEFLATED
from build_advanced_fixtures import text,cell,row,table,header
from build_report_fixture import build as report_entries
from build_fixture import paragraph,run

def build(root,output,kind):
 entries=report_entries(root,output,entries_only=True)
 original=entries['word/document.xml'];prefix=original.split('<w:body>')[0]
 section='<w:sectPr>'+original.split('<w:sectPr>',1)[1].split('</w:sectPr>',1)[0]+'</w:sectPr>'
 if kind=='letter':section=section.replace('w:w="11906" w:h="16838"','w:w="12240" w:h="15840"')
 widths=[1000,6838,1800]
 rows=[header(widths,['Code','Uitgevoerde controles','Uitkomst'])]
 for code,count,split,span in [('A01',2,False,False),('S01',100,True,False),('K01',8,False,False),('S02',100,True,True),('Z01',2,False,False)]:
  content=''.join(text(f'{code}-{index:03d} Controle van de afspraak.',line=240) for index in range(1,count+1))
  cells=[cell(text(code,True),widths[0],fill='EAF3F4')]
  cells.append(cell(content,widths[1]+(widths[2] if span else 0),span=2 if span else 1,fill='F3F7F8' if split else None))
  if not span:cells.append(cell(text('Beoordeeld',align='right'),widths[2]))
  rows.append(row(cells,split=split))
 blocks=paragraph(run('Controleoverzicht met doorlopende tabelrijen'),'<w:pStyle w:val="Title"/>')+paragraph(run('Lange rijen mogen over pagina’s lopen. Een van deze rijen deelt meerdere kolommen. De tabelkop keert terug op iedere vervolgpagina.'))+table(widths,rows)+paragraph(run('Einde van het controleoverzicht.'))
 xml=prefix+'<w:body>'+blocks+section+'</w:body></w:document>'
 border='<w:tcBorders>'+''.join(f'<w:{side} w:val="single" w:sz="4" w:color="B8C5CC"/>' for side in ['top','left','bottom','right'])+'</w:tcBorders>'
 xml,count=re.subn(r'(<w:tcW\b[^>]*/>(?:<w:gridSpan\b[^>]*/>)?)',lambda match:match.group(1)+border,xml)
 assert count==17,'Unexpected cell inventory'
 entries['word/document.xml']=xml
 output.parent.mkdir(parents=True,exist_ok=True)
 with ZipFile(output,'w') as archive:
  for name,value in sorted(entries.items()):
   info=ZipInfo(name,date_time=(2026,10,25,0,0,0));info.create_system=0;info.compress_type=ZIP_DEFLATED
   archive.writestr(info,value.encode('utf-8') if isinstance(value,str) else value)

if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('output',type=Path)
 args=parser.parse_args();root=Path(__file__).resolve().parents[3]
 for kind in ['a4','letter']:build(root,args.output/kind/'source.docx',kind)
