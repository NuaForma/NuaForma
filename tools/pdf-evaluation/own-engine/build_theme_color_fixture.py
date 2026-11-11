"""Synthetic theme-color probes based on reviewed Word table geometry."""
from pathlib import Path
from zipfile import ZipFile,ZipInfo,ZIP_DEFLATED
from xml.sax.saxutils import escape
import argparse,re
W='http://schemas.openxmlformats.org/wordprocessingml/2006/main'
A='http://schemas.openxmlformats.org/drawingml/2006/main'
R='http://schemas.openxmlformats.org/officeDocument/2006/relationships'


def theme_xml(colors):
    slots=''.join(f'<a:{name}><a:srgbClr val="{value}"/></a:{name}>' for name,value in colors.items())
    fill='<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>'
    fonts=''.join(f'<a:{name}Font><a:latin typeface="Liberation Sans"/><a:ea typeface=""/><a:cs typeface=""/></a:{name}Font>' for name in ['major','minor'])
    line='<a:ln w="9525">'+fill+'<a:prstDash val="solid"/></a:ln>'
    formats='<a:fillStyleLst>'+fill*3+'</a:fillStyleLst><a:lnStyleLst>'+line*3+'</a:lnStyleLst><a:effectStyleLst>'+'<a:effectStyle><a:effectLst/></a:effectStyle>'*3+'</a:effectStyleLst><a:bgFillStyleLst>'+fill*3+'</a:bgFillStyleLst>'
    return f'<a:theme xmlns:a="{A}" name="Qualified colors"><a:themeElements><a:clrScheme name="Qualified colors">{slots}</a:clrScheme><a:fontScheme name="Liberation">{fonts}</a:fontScheme><a:fmtScheme name="Qualified">{formats}</a:fmtScheme></a:themeElements></a:theme>'


def run(text,properties=''):
    return f'<w:r><w:rPr>{properties}</w:rPr><w:t xml:space="preserve">{escape(text)}</w:t></w:r>'


def paragraph(text,properties='',style=''):
    return f'<w:p><w:pPr><w:jc w:val="left"/><w:spacing w:before="0" w:after="0" w:line="300" w:lineRule="exact"/>{style}</w:pPr>{run(text,properties)}</w:p>'


def build(base,output):
    cases=[(role,'',None) for role in ['text1','background1','text2','background2','accent1','accent2','accent3','accent4','accent5','accent6','hyperlink','followedHyperlink']]
    cases += [(role,attr,value) for role in ['accent1','accent2','accent3'] for attr,value in [('themeTint','99'),('themeShade','BF'),('themeTint','00'),('themeTint','FF'),('themeShade','00'),('themeShade','FF')]]
    cases += [('accent1','both','99'),('text1','themeTint','A6'),('background1','themeShade','80')]
    for paper,table in [('a4','table-legacy'),('letter','table-letter')]:
        with ZipFile(base/'compatibility-mode-14'/table/'source.docx') as z:parts={n:z.read(n) for n in z.namelist()}
        colors={'dk1':'1F3147','lt1':'D6E2EF','dk2':'204060','lt2':'FFEEDD','accent1':'156082','accent2':'C0504D','accent3':'4F81BD','accent4':'70AD47','accent5':'ED7D31','accent6':'A5A5A5','hlink':'0000FF','folHlink':'800080'}
        if paper=='letter':colors.update(dk1='302010',lt1='F0D0A0',accent1='336699',accent2='7D3880',accent3='BD814F')
        rows=[]
        for i,(role,attr,value) in enumerate(cases):
            transform=f' w:{attr}="{value}"' if attr and attr!='both' else (' w:themeTint="99" w:themeShade="BF"' if attr else '')
            rows.append(paragraph(f'K{i:02d} {role} {attr} {value or "base"}',f'<w:color w:val="FF00FF" w:themeColor="{role}"{transform}/>'))
        rows += [paragraph('S01 paragraph theme inherited',style='<w:pStyle w:val="ThemeBody"/>'),
                 paragraph('S02 character theme overrides paragraph','<w:rStyle w:val="ThemeCharacter"/>','<w:pStyle w:val="ThemeBody"/>'),
                 paragraph('S03 direct RGB overrides inherited theme','<w:rStyle w:val="ThemeCharacter"/><w:color w:val="125634"/>','<w:pStyle w:val="ThemeBody"/>')]
        document=parts['word/document.xml'].decode();document=re.sub(r'(?<=>)C([0-9]{2})',r'T\1',document)
        rows.append(paragraph('Qualified table colors', '<w:color w:themeColor="accent1"/>', '<w:pageBreakBefore/>'))
        document=document.replace('<w:body>','<w:body>'+''.join(rows))
        parts['word/document.xml']=document.encode()
        styles=parts['word/styles.xml'].decode();styles=styles.replace('<w:color w:val="008B90"/>','<w:color w:val="FF00FF" w:themeColor="accent2" w:themeShade="BF"/>')
        additions='<w:style w:type="paragraph" w:styleId="ThemeBody"><w:name w:val="Theme body"/><w:basedOn w:val="Normal"/><w:rPr><w:color w:themeColor="accent1"/></w:rPr></w:style>'
        additions+='<w:style w:type="character" w:styleId="ThemeCharacter"><w:name w:val="Theme character"/><w:rPr><w:color w:themeColor="accent3" w:themeTint="99"/></w:rPr></w:style>'
        parts['word/styles.xml']=styles.replace('</w:styles>',additions+'</w:styles>').encode()
        parts['word/theme/theme1.xml']=theme_xml(colors).encode()
        parts['word/_rels/document.xml.rels']=parts['word/_rels/document.xml.rels'].decode().replace('</Relationships>',f'<Relationship Id="theme245" Type="{R}/theme" Target="theme/theme1.xml"/></Relationships>').encode()
        parts['[Content_Types].xml']=parts['[Content_Types].xml'].decode().replace('</Types>','<Override PartName="/word/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/></Types>').encode()
        target=output/paper;target.mkdir(parents=True,exist_ok=False)
        with ZipFile(target/'source.docx','x',ZIP_DEFLATED) as z:
            for name,data in parts.items():
                info=ZipInfo(name,(2025,1,1,0,0,0));info.create_system=0;info.compress_type=ZIP_DEFLATED;z.writestr(info,data)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--base',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args();build(args.base,args.output)
