"""Independent source, full-glyph and solid-border checks for table combinations."""
import argparse,json
from pathlib import Path
from zipfile import ZipFile
from xml.etree import ElementTree as ET
from audit_cell_alignment import audit as audit_glyphs
from audit_cell_borders import stroke_audit

W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'

def main():
 parser=argparse.ArgumentParser(description=__doc__)
 for name in ['source','reference','candidate','output']:parser.add_argument(name,type=Path)
 args=parser.parse_args()
 result=audit_glyphs(args.source,args.reference,args.candidate,all_table_headers=True)
 with ZipFile(args.source) as archive:body=ET.fromstring(archive.read('word/document.xml')).find(W+'body')
 palette={tuple(bytes.fromhex(node.get(W+'color'))) for node in body.iter() if node.tag in {W+side for side in ['top','right','bottom','left','insideH','insideV']} and node.get(W+'val')=='single'}
 result['borders']=stroke_audit(args.reference,args.candidate,palette,args.output)
 args.output.write_text(json.dumps(result,indent=2),encoding='utf-8')
 print(json.dumps(result),flush=True)
 assert all(c['relativeAreaDifference']<=.05 and not c['missingBeyondQuarterPoint'] and not c['extraBeyondQuarterPoint'] for page in result['borders'] for c in page['colors']),'Visible border footprint differs from Word'

if __name__=='__main__':main()
