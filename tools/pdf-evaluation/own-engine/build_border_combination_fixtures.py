"""Consistent cell borders combined with existing synthetic table profiles."""
import argparse,re,tempfile
from pathlib import Path
from zipfile import ZipFile,ZipInfo,ZIP_DEFLATED
from xml.etree import ElementTree as ET
from build_advanced_fixtures import build as advanced
from build_report_fixture import build as report
from build_page_format_fixtures import build as paper

KINDS=['merged','nested','pages','letter','split']

def build(root,output,kind):
 with tempfile.TemporaryDirectory() as temporary:
  source=Path(temporary)/'source.docx'
  if kind in ['merged','nested','split']:advanced(root,source,kind)
  elif kind=='letter':paper(root,source,'letter-portrait')
  else:report(root,source)
  with ZipFile(source) as archive:entries={name:archive.read(name) for name in archive.namelist()}
 xml=entries['word/document.xml'].decode('utf-8')
 border='<w:tcBorders>'+''.join(f'<w:{side} w:val="single" w:sz="4" w:color="B8C5CC"/>' for side in ['top','left','bottom','right'])+'</w:tcBorders>'
 xml,count=re.subn(r'(<w:tcW\b[^>]*/>(?:<w:gridSpan\b[^>]*/>)?(?:<w:vMerge\b[^>]*/>)?)',lambda match:match.group(1)+border,xml)
 assert count>0,'Missing source cells'
 ns='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
 order={'tcW':0,'gridSpan':1,'vMerge':2,'tcBorders':3,'shd':4}
 for properties in ET.fromstring(xml).iter(ns+'tcPr'):
  positions=[order[node.tag.removeprefix(ns)] for node in properties]
  assert positions==sorted(positions),'Invalid synthetic cell-property order'
 entries['word/document.xml']=xml.encode('utf-8')
 output.parent.mkdir(parents=True,exist_ok=True)
 with ZipFile(output,'w') as archive:
  for name,value in sorted(entries.items()):
   info=ZipInfo(name,date_time=(2026,10,25,0,0,0));info.create_system=0;info.compress_type=ZIP_DEFLATED
   archive.writestr(info,value)

if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('output',type=Path)
 args=parser.parse_args();root=Path(__file__).resolve().parents[3]
 for kind in KINDS:build(root,args.output/kind/'source.docx',kind)
