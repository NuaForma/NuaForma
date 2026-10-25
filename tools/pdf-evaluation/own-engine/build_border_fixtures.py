"""Empirical solid cell-border profiles; body content determines row height."""
import argparse
from pathlib import Path
from zipfile import ZipFile,ZipInfo,ZIP_DEFLATED
ROOT=Path(__file__).resolve().parents[3]
from build_advanced_fixtures import text,cell,row,table,header
from build_report_fixture import build as report_entries
from build_fixture import paragraph,run

def edges(values):
 return '<w:tcBorders>'+''.join(f'<w:{side} w:val="{values[side][0]}"'+(f' w:sz="{values[side][1]}" w:color="{values[side][2]}"' if values[side][0]=='single' else '')+'/>' for side in ['top','left','bottom','right'] if side in values)+'</w:tcBorders>'

def build_conflicting(kind,output):
 entries=report_entries(ROOT,output,entries_only=True)
 original=entries['word/document.xml'];prefix=original.split('<w:body>')[0]
 section='<w:sectPr>'+original.split('<w:sectPr>',1)[1].split('</w:sectPr>',1)[0]+'</w:sectPr>'
 widths=[2000,3819,3819];rows=[header(widths,['Code','Controle','Afspraak'])]
 colors=['00999F','00518C','B8C5CC','7A3040']
 for i in range(1,13):
  cells=[]
  for col,width in enumerate(widths):
   values={}
   if kind=='colors':
    values={side:('single',4,colors[(i+col+j)%len(colors)]) for j,side in enumerate(['top','right','bottom','left'])}
   elif kind=='weights':
    values={side:('single',[4,8,16,32][(i+col+j)%4],colors[(i+col)%4]) for j,side in enumerate(['top','right','bottom','left'])}
    if i%4==0:values['right']=('none',0,'000000')
   else:
    values={side:('none' if (i+col)%2 else 'nil',0,'000000') for side in ['top','right','bottom','left']}
    if i%4==0:values['bottom']=('single',8,'00518C')
   content=text(f'B{i:02d}C{col+1}'+(' Controle vastgelegd.' if col else ''))+text('Gegevens en afspraak akkoord.' if col else 'Vastgelegd.')
   value=cell(content,width,fill='F3F7F8' if i%2==0 else None)
   width_node=f'<w:tcW w:w="{width}" w:type="dxa"/>'
   cells.append(value.replace(width_node,width_node+edges(values),1))
  rows.append(row(cells))
 titles={'colors':'Tabellen met gekleurde celranden','weights':'Tabellen met verschillende lijndiktes','outline':'Tabellen met ontbrekende celranden'}
 blocks=paragraph(run(titles[kind]),'<w:pStyle w:val="Title"/>')+paragraph(run('Deze proef controleert celranden en hun invloed op tekstposities. Buurcellen kunnen verschillende randen hebben.'))+table(widths,rows)+paragraph(run('Einde van het randenoverzicht.'))
 entries['word/document.xml']=prefix+'<w:body>'+blocks+section+'</w:body></w:document>'
 output.parent.mkdir(parents=True,exist_ok=True)
 with ZipFile(output,'w') as archive:
  for name,value in sorted(entries.items()):
   info=ZipInfo(name,date_time=(2026,10,25,0,0,0));info.create_system=0;info.compress_type=ZIP_DEFLATED
   archive.writestr(info,value.encode('utf-8') if isinstance(value,str) else value)

def build(kind,output):
 entries=report_entries(ROOT,output,entries_only=True)
 original=entries['word/document.xml'];prefix=original.split('<w:body>')[0]
 section='<w:sectPr>'+original.split('<w:sectPr>',1)[1].split('</w:sectPr>',1)[0]+'</w:sectPr>'
 widths=[2000,3819,3819]
 titles={'colors':'Tabellen met gekleurde celranden','weights':'Tabellen met verschillende lijndiktes','outline':'Tabellen met ontbrekende celranden'}
 blocks=paragraph(run(titles[kind]),'<w:pStyle w:val="Title"/>')+paragraph(run('Elke tabel gebruikt een consistente lijnstijl. De proef controleert kleuren, lijndiktes en bewust weggelaten randen.'))
 for group in range(4):
  rgb=['00999F','00518C','B8C5CC','7A3040'][group] if kind=='colors' else '00518C'
  size=[4,8,16,32][group] if kind=='weights' else 4
  rows=[]
  for offset in range(3):
   i=group*3+offset+1;cells=[]
   for col,width in enumerate(widths):
    values={side:('single',size,rgb) for side in ['top','right','bottom','left']}
    if kind=='outline':
     values={side:('none' if (i+col)%2 else 'nil',0,'000000') for side in ['top','right','bottom','left']}
    content=text(f'B{i:02d}C{col+1}'+(' Controle vastgelegd.' if col else ''))+text('Gegevens en afspraak akkoord.' if col else 'Vastgelegd.')
    value=cell(content,width,fill='F3F7F8' if i%2==0 else None)
    width_node=f'<w:tcW w:w="{width}" w:type="dxa"/>'
    cells.append(value.replace(width_node,width_node+edges(values),1))
   rows.append(row(cells))
  value=table(widths,rows).replace('w:sz="4" w:color="B8C5CC"',f'w:sz="{size}" w:color="{rgb}"')
  blocks+=value+text('',line=120,size=4)
 blocks+=paragraph(run('Einde van het randenoverzicht.'))
 entries['word/document.xml']=prefix+'<w:body>'+blocks+section+'</w:body></w:document>'
 output.parent.mkdir(parents=True,exist_ok=True)
 with ZipFile(output,'w') as archive:
  for name,value in sorted(entries.items()):
   info=ZipInfo(name,date_time=(2026,10,25,0,0,0));info.create_system=0;info.compress_type=ZIP_DEFLATED
   archive.writestr(info,value.encode('utf-8') if isinstance(value,str) else value)

if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('output',type=Path)
 parser.add_argument('--conflicting',action='store_true',help='Generate unqualified corner-conflict research cases')
 args=parser.parse_args()
 for kind in ['colors','weights','outline']:(build_conflicting if args.conflicting else build)(kind,args.output/kind/'source.docx')
