"""Effective styles, theme Latin fonts, automatic spacing and base table styles."""
import argparse
from pathlib import Path
from zipfile import ZipFile,ZipInfo,ZIP_DEFLATED
from build_report_fixture import build as report
from build_fixture import paragraph,run,W,R

def build(root,output):
    entries=report(root,output,entries_only=True)
    old=entries['word/document.xml'];prefix=old.split('<w:body>')[0];section='<w:sectPr>'+old.split('<w:sectPr>')[1].split('</w:sectPr>')[0]+'</w:sectPr>'
    # Keep inherited band styles exact; new body styles explicitly select automatic line boxes.
    styles=entries['word/styles.xml']
    def style(id,parent='Normal',pp='',rp='',kind='paragraph'):
        return f'<w:style w:type="{kind}" w:styleId="{id}"><w:name w:val="{id}"/><w:basedOn w:val="{parent}"/><w:pPr>{pp}</w:pPr><w:rPr>{rp}</w:rPr></w:style>'
    base='<w:spacing w:before="0" w:after="120" w:line="240" w:lineRule="auto"/>'
    font='<w:rFonts w:asciiTheme="minorHAnsi" w:hAnsiTheme="minorHAnsi"/>'
    styles=styles.replace('</w:styles>',style('AutoBase',pp=base,rp=font)+style('AutoDerived','AutoBase',rp='<w:b/><w:color w:val="008B90"/>')+style('AutoToggle','AutoDerived',rp='<w:b/><w:i/>')+style('AutoOff','AutoDerived',rp='<w:b w:val="0"/>')+style('Context','AutoBase',pp='<w:contextualSpacing/><w:spacing w:before="240" w:after="240"/>')+style('CharBase',parent='NoParent',kind='character',rp='<w:b/>').replace('<w:basedOn w:val="NoParent"/>','')+style('CharDerived','CharBase',kind='character',rp='<w:b/><w:i/>')+'</w:styles>')
    table_style='<w:style w:type="table" w:styleId="TableBase"><w:name w:val="Table Base"/><w:pPr><w:spacing w:before="0" w:after="0" w:line="320" w:lineRule="exact"/></w:pPr><w:rPr><w:b/><w:color w:val="008B90"/></w:rPr></w:style>'
    table_style+='<w:style w:type="table" w:styleId="TableDerived"><w:name w:val="Table Derived"/><w:basedOn w:val="TableBase"/><w:rPr><w:i/></w:rPr></w:style>'
    entries['word/styles.xml']=styles.replace('</w:styles>',table_style+'</w:styles>')
    a='http://schemas.openxmlformats.org/drawingml/2006/main'
    entries['word/theme/theme1.xml']=f'<a:theme xmlns:a="{a}" name="NuaForma"><a:themeElements><a:clrScheme name="NuaForma"><a:dk1><a:srgbClr val="000000"/></a:dk1><a:lt1><a:srgbClr val="FFFFFF"/></a:lt1><a:dk2><a:srgbClr val="333333"/></a:dk2><a:lt2><a:srgbClr val="EEEEEE"/></a:lt2><a:accent1><a:srgbClr val="008B90"/></a:accent1><a:accent2><a:srgbClr val="663399"/></a:accent2><a:accent3><a:srgbClr val="4472C4"/></a:accent3><a:accent4><a:srgbClr val="70AD47"/></a:accent4><a:accent5><a:srgbClr val="ED7D31"/></a:accent5><a:accent6><a:srgbClr val="A5A5A5"/></a:accent6><a:hlink><a:srgbClr val="0000FF"/></a:hlink><a:folHlink><a:srgbClr val="800080"/></a:folHlink></a:clrScheme><a:fontScheme name="NuaForma"><a:majorFont><a:latin typeface="Liberation Sans"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont><a:minorFont><a:latin typeface="Liberation Sans"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont></a:fontScheme><a:fmtScheme name="NuaForma"><a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:fillStyleLst><a:lnStyleLst><a:ln w="9525"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:prstDash val="solid"/></a:ln><a:ln w="9525"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:prstDash val="solid"/></a:ln><a:ln w="9525"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:prstDash val="solid"/></a:ln></a:lnStyleLst><a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst><a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>'
    entries['word/_rels/document.xml.rels']=entries['word/_rels/document.xml.rels'].replace('</Relationships>',f'<Relationship Id="theme" Type="{R}/theme" Target="theme/theme1.xml"/></Relationships>')
    entries['[Content_Types].xml']=entries['[Content_Types].xml'].replace('</Types>','<Override PartName="/word/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/></Types>')
    content=[]
    def p(id,text,style='AutoBase',props='',runs=None):content.append(paragraph(runs if runs is not None else run(id+' '+text),f'<w:pStyle w:val="{style}"/>'+props))
    content.append(paragraph(run('Stijlen en automatische regelafstand'),'<w:pStyle w:val="Title"/>'))
    p('A01','Deze proef controleert de uiteindelijke opmaak na overerving van stijlen en directe aanpassingen. Logo en paginabanden blijven behouden.')
    for id,m in [('A02',240),('A03',360),('A04',480)]:p(id,'Deze langere alinea loopt over meerdere regels en maakt de automatische regelafstand zichtbaar. De tekst blijft leesbaar en volledig behouden bij het instellen van enkele, anderhalve of dubbele regelafstand.',props=f'<w:spacing w:line="{m}" w:lineRule="auto"/>')
    p('S01','Afgeleide stijl met vet en een themalettertype.','AutoDerived')
    p('S02','Tweede afgeleide behoudt vet en voegt cursief toe.','AutoToggle')
    p('S03','Directe opmaak overschrijft de afgeleide stijl.','AutoDerived',runs=run('S03 Directe opmaak overschrijft de afgeleide stijl.','<w:b w:val="0"/><w:color w:val="663399"/>'))
    p('S04','',runs=run('S04 Tekenstijl ')+run('behoudt vet en voegt cursief toe','<w:rStyle w:val="CharDerived"/>')+run(' en directe opmaak zet cursief uit.','<w:rStyle w:val="CharDerived"/><w:i w:val="0"/>'))
    p('S05','Een expliciete uitwaarde verwijdert de geërfde vetopmaak.','AutoOff')
    content.append(paragraph(run('Alinea en tabelstijlen'),'<w:pStyle w:val="Title"/><w:pageBreakBefore/>'))
    p('C01','Eerste alinea met contextuele afstand.','Context')
    p('C02','Zelfde stijl onderdrukt de tussenruimte.','Context')
    p('C03','Derde alinea met dezelfde stijl.','Context')
    p('C04','Een andere stijl herstelt de normale tussenruimte.')
    props='<w:tblStyle w:val="TableDerived"/><w:tblW w:w="8000" w:type="dxa"/><w:tblInd w:w="120" w:type="dxa"/><w:tblLayout w:type="fixed"/><w:tblBorders>'+''.join(f'<w:{side} w:val="single" w:sz="4" w:color="B8C5CC"/>' for side in ['top','left','bottom','right','insideH','insideV'])+'</w:tblBorders><w:tblCellMar>'+''.join(f'<w:{side} w:w="120" w:type="dxa"/>' for side in ['top','left','bottom','right'])+'</w:tblCellMar>'
    rows=[]
    for n in range(1,4):
        cells=[]
        for c in range(2):
            text=f'T{n}{c} '+('Tabelstijl met overerving' if c==0 else 'Directe celtekst')
            rp='<w:b w:val="0"/><w:i w:val="0"/><w:color w:val="333333"/>' if c else ''
            # No explicit paragraph style: table style sits between doc defaults and paragraph style.
            cells.append('<w:tc><w:tcPr><w:tcW w:w="4000" w:type="dxa"/></w:tcPr>'+paragraph(run(text,rp))+'</w:tc>')
        rows.append('<w:tr><w:trPr><w:cantSplit/></w:trPr>'+''.join(cells)+'</w:tr>')
    content.append('<w:tbl><w:tblPr>'+props+'</w:tblPr><w:tblGrid><w:gridCol w:w="4000"/><w:gridCol w:w="4000"/></w:tblGrid>'+''.join(rows)+'</w:tbl>')
    p('P01','Deze alinea volgt op de tabel en behoudt de gewone documentopmaak.')
    entries['word/document.xml']=prefix+'<w:body>'+''.join(content)+section+'</w:body></w:document>'
    output.parent.mkdir(parents=True,exist_ok=True)
    with ZipFile(output,'w') as archive:
        for name,value in sorted(entries.items()):
            info=ZipInfo(name,date_time=(2026,10,24,0,0,0));info.compress_type=ZIP_DEFLATED;info.create_system=0
            archive.writestr(info,value.encode('utf-8') if isinstance(value,str) else value)

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('output',type=Path);args=p.parse_args();build(Path(__file__).resolve().parents[3],args.output)
