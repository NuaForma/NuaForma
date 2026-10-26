"""Bounded cell-alignment trials over natural row heights and fixed source grids."""
from pathlib import Path
from zipfile import ZipFile,ZipInfo,ZIP_DEFLATED
import argparse
ROOT=Path(__file__).resolve().parents[3]
from build_advanced_fixtures import text,cell,row,table,header
from build_report_fixture import build as report_entries
from build_fixture import paragraph,run

def aligned(content,width,alignment='top',**kwargs):
 return cell(content,width,**kwargs).replace('</w:tcPr>',f'<w:vAlign w:val="{alignment}"/></w:tcPr>',1)

def ordinary():
 widths=[1200,1200,1200,6038]
 rows=[header(widths,['Boven','Midden','Onder','Toelichting'])]
 for i in range(1,19):
  content=''.join(text(f'A{i:02d}-{j} Controle van de registratie en afspraak.') for j in range(1,5))
  rows.append(row([aligned(text(f'T{i:02d} boven'),1200),aligned(text(f'C{i:02d} midden'),1200,'center'),aligned(text(f'B{i:02d} onder'),1200,'bottom'),aligned(content,6038)],split=False))
 return table(widths,rows)

def merged():
 widths=[1600,1600,6438]
 rows=[header(widths,['Groep','Code','Toelichting'])]
 for group in range(1,5):
  mode='center' if group%2 else 'bottom'
  for n in range(1,4):
   content=text(f'M{group}{n} Eerste controle van de gegevens.')+text('Aanvullende afspraak vastgelegd.')
   first=aligned(text(f'Groep {group}') if n==1 else '<w:p/>',1600,mode,vertical='restart' if n==1 else 'continue',fill='EAF3F4')
   rows.append(row([first,aligned(text(f'G{group}{n}'),1600,'bottom'),aligned(content,6438)]))
 rows.append(row([aligned(text('Twee kolommen midden'),3200,'center',span=2),aligned(text('Controle een')+text('Controle twee')+text('Controle drie'),6438)]))
 return table(widths,rows)

def nested():
 widths=[1400,1400,6838]
 rows=[header(widths,['Midden','Onder','Detailtabel'])]
 for i in range(1,6):
  inner=table([1800,4798],[row([aligned(text(f'N{i}A'),1800,'center'),aligned(text('Controle uitgevoerd')+text('Gegevens vastgelegd'),4798)]),row([aligned(text(f'N{i}B'),1800,'bottom'),aligned(text('Uitkomst akkoord')+text('Afspraak bevestigd'),4798)])],padding=(40,60,40,60))
  rows.append(row([aligned(text(f'Midden {i}'),1400,'center'),aligned(text(f'Onder {i}'),1400,'bottom'),aligned(inner+text('',size=4,line=80),6838)]))
 return table(widths,rows)

def build(root,output,kind):
 entries=report_entries(root,output,entries_only=True)
 original=entries['word/document.xml'];prefix=original.split('<w:body>')[0]
 section='<w:sectPr>'+original.split('<w:sectPr>',1)[1].split('</w:sectPr>',1)[0]+'</w:sectPr>'
 titles={'ordinary':'Verticale uitlijning in tabelcellen','merged':'Verticale uitlijning in samengevoegde cellen','nested':'Verticale uitlijning met detailtabellen'}
 intro='Deze proef vergelijkt tekst bovenaan, middenin en onderaan cellen. De inhoud bepaalt de rijhoogte; kolombreedtes en celmarges komen uit het document.'
 blocks=paragraph(run(titles[kind]),'<w:pStyle w:val="Title"/>')+paragraph(run(intro))+{'ordinary':ordinary,'merged':merged,'nested':nested}[kind]()+paragraph(run('Einde van het uitlijningsoverzicht.'))
 entries['word/document.xml']=prefix+'<w:body>'+blocks+section+'</w:body></w:document>'
 output.parent.mkdir(parents=True,exist_ok=True)
 with ZipFile(output,'w') as archive:
  for name,value in sorted(entries.items()):
   info=ZipInfo(name,date_time=(2026,10,25,0,0,0));info.create_system=0;info.compress_type=ZIP_DEFLATED
   archive.writestr(info,value.encode('utf-8') if isinstance(value,str) else value)

if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('output',type=Path)
 args=parser.parse_args()
 for kind in ['ordinary','merged','nested']:build(ROOT,args.output/kind/'source.docx',kind)
