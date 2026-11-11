"""Build bounded mixed ASCII/high-ANSI sources from existing licensed theme fixtures."""
import argparse
from pathlib import Path
from zipfile import ZipFile,ZipInfo,ZIP_DEFLATED
from xml.sax.saxutils import escape
W='http://schemas.openxmlformats.org/wordprocessingml/2006/main'
R='http://schemas.openxmlformats.org/officeDocument/2006/relationships'


def run(text,fonts='',style='',extra=''):
    prop=('<w:rStyle w:val="'+style+'"/>' if style else '')+('<w:rFonts '+fonts+'/>' if fonts else '')+extra
    return '<w:r><w:rPr>'+prop+'</w:rPr><w:t xml:space="preserve">'+escape(text)+'</w:t></w:r>'


def paragraph(content,style='',extra=''):
    return '<w:p><w:pPr>'+('<w:pStyle w:val="'+style+'"/>' if style else '')+extra+'</w:pPr>'+content+'</w:p>'


def definition(name,fonts,kind='paragraph',parent='Normal'):
    return '<w:style w:type="'+kind+'" w:styleId="'+name+'"><w:name w:val="'+name+'"/>'+('<w:basedOn w:val="'+parent+'"/>' if parent else '')+'<w:rPr><w:rFonts '+fonts+'/></w:rPr></w:style>'


def build(base,output):
    for paper in ['a4','letter']:
        with ZipFile(base/'theme-colors'/paper/'source.docx') as z:parts={n:z.read(n) for n in z.namelist()}
        previous=parts['word/document.xml'].decode();prefix=previous.split('<w:body>')[0];section='<w:sectPr>'+previous.split('<w:sectPr>')[1].split('</w:sectPr>')[0]+'</w:sectPr>'
        body=paragraph(run('R00 Mixed Latin fonts — '+paper.upper()))
        body+=paragraph(run('R01 ASCII serif; É01 élève € Ω01 Ελληνικά Ж01 Отчёт.', 'w:ascii="Noto Serif"'))
        body+=paragraph(run('R02 ASCII sans; É02 élève € Ω02 Ελληνικά Ж02 Отчёт.', 'w:hAnsi="Noto Serif"'))
        body+=paragraph(run('R03 inherited ASCII; É03 café.',style='CharAnsi'),style='AsciiSerif')
        body+=paragraph(run('R04 inherited high ANSI; É04 café.', 'w:ascii="Noto Serif"'),style='AnsiSerif')
        body+=paragraph(run('R05 direct ASCII overrides inherited theme; É05 café.', 'w:ascii="Liberation Sans"'),style='ThemeAscii')
        body+=paragraph(run('R06 direct theme overrides inherited ASCII; É06 café.', 'w:asciiTheme="majorAscii"'))
        body+=paragraph(run('R07 theme overrides same-element stale cache; É07 café.', 'w:ascii="Liberation Sans" w:asciiTheme="majorAscii"'))
        body+=paragraph(run('R08 high ANSI theme; É08 café.', 'w:hAnsi="Liberation Sans" w:hAnsiTheme="majorHAnsi"'))
        body+=paragraph('<w:bookmarkStart w:id="248" w:name="Mixed248"/>'+run('R09 bookmarked É09 café.', 'w:hAnsi="Noto Serif"')+'<w:bookmarkEnd w:id="248"/>')
        body+=paragraph('<w:hyperlink w:anchor="Mixed248">'+run('R10 linked É10 café.', 'w:hAnsi="Noto Serif"',extra='<w:color w:val="0000FF"/><w:u w:val="single"/>')+'</w:hyperlink>')
        field='R11 Property É11 café.'
        parts['docProps/core.xml']=('<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:description>'+field+'</dc:description></cp:coreProperties>').encode()
        parts['_rels/.rels']=parts['_rels/.rels'].decode().replace('</Relationships>','<Relationship Id="core248" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/></Relationships>').encode()
        parts['[Content_Types].xml']=parts['[Content_Types].xml'].decode().replace('</Types>','<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/></Types>').encode()
        body+=paragraph('<w:fldSimple w:instr=" DOCPROPERTY Comments ">'+run(field,'w:hAnsi="Noto Serif"')+'</w:fldSimple>')
        body+=paragraph(run('R12 Wrapped '+('ASCII café Ωμέγα Отчёт – ')*12,'w:hAnsi="Noto Serif"'),extra='<w:spacing w:before="0" w:after="0" w:line="240" w:lineRule="auto"/>')
        body+=paragraph(run('R13 Second physical page.'),extra='<w:pageBreakBefore/>')
        cells=[]
        for index in range(3):
            row=''
            for col in range(2):
                text=f'R{14+index*2+col:02} Cell É{14+index*2+col:02} café.'
                row+='<w:tc><w:tcPr><w:tcW w:w="4300" w:type="dxa"/></w:tcPr>'+paragraph(run(text,'w:hAnsi="Noto Serif"'))+'</w:tc>'
            cells.append('<w:tr>'+row+'</w:tr>')
        body+='<w:tbl><w:tblPr><w:tblW w:w="8600" w:type="dxa"/><w:tblLayout w:type="fixed"/><w:tblInd w:w="0" w:type="dxa"/><w:tblBorders/><w:tblCellMar><w:top w:w="0" w:type="dxa"/><w:left w:w="108" w:type="dxa"/><w:bottom w:w="0" w:type="dxa"/><w:right w:w="108" w:type="dxa"/></w:tblCellMar></w:tblPr><w:tblGrid><w:gridCol w:w="4300"/><w:gridCol w:w="4300"/></w:tblGrid>'+''.join(cells)+'</w:tbl>'
        body+=paragraph(run('R20 End É20 café.', 'w:hAnsi="Noto Serif"'))
        for kind,label in [('header','H01'),('footer','F01')]:
            name=kind+'1.xml';parts['word/'+name]=(f'<w:{dict(header='hdr',footer='ftr')[kind]} xmlns:w="{W}">'+paragraph(run(label+' Mixed band É'+label[1:]+' café.','w:hAnsi="Noto Serif"'))+f'</w:{dict(header='hdr',footer='ftr')[kind]}>').encode()
            rels=parts['word/_rels/document.xml.rels'].decode();rels=rels.replace('</Relationships>',f'<Relationship Id="mixed{kind}" Type="{R}/{kind}" Target="{name}"/></Relationships>');parts['word/_rels/document.xml.rels']=rels.encode()
            ct=parts['[Content_Types].xml'].decode();ct=ct.replace('</Types>',f'<Override PartName="/word/{name}" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.{kind}+xml"/></Types>');parts['[Content_Types].xml']=ct.encode()
            section=section.replace('<w:sectPr>',f'<w:sectPr><w:{kind}Reference w:type="default" r:id="mixed{kind}"/>')
        parts['word/document.xml']=(prefix+'<w:body>'+body+section+'</w:body></w:document>').encode()
        styles=f'<w:styles xmlns:w="{W}"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="24"/><w:lang w:val="en-US"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:before="0" w:after="0" w:line="400" w:lineRule="exact"/><w:widowControl w:val="0"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:pPr><w:jc w:val="left"/></w:pPr></w:style>'
        styles+=definition('AsciiSerif','w:ascii="Noto Serif"')+definition('AnsiSerif','w:hAnsi="Noto Serif"')+definition('ThemeAscii','w:asciiTheme="majorAscii"')+definition('CharAnsi','w:hAnsi="Noto Serif"','character','')+'</w:styles>';parts['word/styles.xml']=styles.encode()
        theme=parts['word/theme/theme1.xml'].decode();start=theme.index('<a:majorFont>');end=theme.index('</a:majorFont>')
        segment=theme[start:end];import re
        changed=re.sub(r'(<a:latin typeface=")[^"]*(")',r'\g<1>Noto Serif\2',segment,count=1);assert changed!=segment
        parts['word/theme/theme1.xml']=(theme[:start]+changed+theme[end:]).encode()
        target=output/paper;target.mkdir(parents=True,exist_ok=False)
        with ZipFile(target/'source.docx','x',ZIP_DEFLATED) as z:
            for name,data in parts.items():
                item=ZipInfo(name,(2025,1,1,0,0,0));item.create_system=0;item.compress_type=ZIP_DEFLATED;z.writestr(item,data)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--base',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args();build(args.base,args.output)
