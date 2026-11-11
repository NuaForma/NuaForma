"""Build equivalent explicit/inherited table-margin and ordinary-border probes."""
import argparse
from pathlib import Path
from zipfile import ZipFile,ZipInfo,ZIP_DEFLATED
import re
def margins(top=0,left=108,bottom=0,right=108):
 return '<w:tblCellMar>'+''.join(f'<w:{key} w:w="{value}" w:type="dxa"/>' for key,value in [('top',top),('left',left),('bottom',bottom),('right',right)])+'</w:tblCellMar>'
styles='<w:style w:type="table" w:default="1" w:styleId="NativeDefaultTable"><w:name w:val="Native default table"/><w:tblPr>'+margins()+'</w:tblPr></w:style><w:style w:type="table" w:styleId="NativeDerivedTable"><w:name w:val="Native derived table"/><w:basedOn w:val="NativeDefaultTable"/><w:tblPr><w:tblCellMar><w:left w:w="216" w:type="dxa"/><w:bottom w:w="80" w:type="dxa"/></w:tblCellMar></w:tblPr></w:style>'

def build(base, output):
    for paper in ['a4','letter']:
     with ZipFile(base/paper/'source.docx') as z:original={n:z.read(n) for n in z.namelist()}
     for mode in ['control','inherited']:
      document=original['word/document.xml'].decode().replace('PREFERRED TABLE WIDTHS','INHERITED TABLE MARGINS')
      index=0

      def properties(match):
       nonlocal index
       i=index;index+=1;s=match.group()
       if i==3:s=s.replace('<w:tblPr>','<w:tblPr><w:tblStyle w:val="NativeDerivedTable"/>')
       pad=margins() if i<2 else margins(top=113,bottom=113) if i==2 else margins(top=40,left=216,bottom=80,right=54)
       if mode=='inherited':
        pad='' if i<2 else '<w:tblCellMar><w:top w:w="113" w:type="dxa"/><w:bottom w:w="113" w:type="dxa"/></w:tblCellMar>' if i==2 else '<w:tblCellMar><w:top w:w="40" w:type="dxa"/><w:right w:w="54" w:type="dxa"/></w:tblCellMar>'
       s=re.sub(r'<w:tblCellMar>.*?</w:tblCellMar>',lambda _:pad,s)
       border='<w:tblBorders>'
       if mode=='inherited':border+=''.join(f'<w:{side} w:val="{"none" if i==0 else "single"}" w:sz="{0 if i==0 else 4}" w:color="auto" w:space="0"/>' for side in ['top','left','bottom','right','insideH','insideV'])
       elif i!=0:border+=''.join(f'<w:{side} w:val="single" w:sz="4" w:color="000000"/>' for side in ['top','left','bottom','right','insideH','insideV'])
       border+='</w:tblBorders>';return re.sub(r'<w:tblBorders>.*?</w:tblBorders>',lambda _:border,s)

      document=re.sub(r'<w:tblPr>.*?</w:tblPr>',properties,document);assert index==4
      parts=dict(original);parts['word/document.xml']=document.encode();parts['word/styles.xml']=parts['word/styles.xml'].decode().replace('</w:styles>',styles+'</w:styles>').encode()
      if 'word/stylesWithEffects.xml' in parts:parts['word/stylesWithEffects.xml']=parts['word/stylesWithEffects.xml'].decode().replace('</w:styles>',styles+'</w:styles>').encode()
      dest=output/mode/paper;dest.mkdir(parents=True,exist_ok=False)
      with ZipFile(dest/'source.docx','x',ZIP_DEFLATED) as z:
       for name,data in parts.items():
        info=ZipInfo(name,(2025,1,1,0,0,0));info.create_system=0;info.compress_type=ZIP_DEFLATED;z.writestr(info,data)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    build(args.base/'preferred-table-widths', args.output)
