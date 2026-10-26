"""Controlled page-band, VML text-frame and rotation fixtures; standard library only."""
import argparse
from pathlib import Path
import struct
import zlib
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
from build_report_fixture import build as report_entries
from build_fixture import paragraph, run, R


def p(text, align='left', size=20, bold=False, line=280):
    return paragraph(run(text, f'<w:sz w:val="{size}"/>' + ('<w:b/>' if bold else '')),
        f'<w:spacing w:before="0" w:after="0" w:line="{line}" w:lineRule="exact"/><w:jc w:val="{align}"/>')


def picture(inline, relation, width, height):
    import re
    inline=re.sub(r'(cx|cy)="\d+"',lambda m:f'{m[1]}="{round((width if m[1]=="cx" else height)*12700)}"',inline)
    return '<w:r><w:drawing>'+inline.replace('r:embed="logo"',f'r:embed="{relation}"')+'</w:drawing></w:r>'


def frame(name, x,y,w,h,content,rotation=0,vertical='page',align='top',fill='EAF3F4',wrap=False,padding='6pt,3pt,6pt,3pt'):
    style=f'position:absolute;margin-left:{x}pt;margin-top:{y}pt;width:{w}pt;height:{h}pt;rotation:{rotation};mso-position-horizontal-relative:page;mso-position-vertical-relative:{vertical};v-text-anchor:{align}'
    decoration=f'filled="t" fillcolor="#{fill}" stroked="t" strokecolor="#008B90" strokeweight="0.5pt"' if fill else 'filled="f" stroked="f"'
    return f'<w:r><w:pict><v:rect id="{name}" style="{style}" {decoration}><v:textbox inset="{padding}"><w:txbxContent>{content}</w:txbxContent></v:textbox>'+('<w10:wrap type="topAndBottom"/>' if wrap else '')+'</v:rect></w:pict></w:r>'


def barcode_png():
    # Fixed ISO/IEC Code 39 test vector for NF20260057, without Mod-43 checksum.
    pattern='bSbsBsBsbibsbsBsbSBibsBsBSbsbibsBSbsbsBibsbSBsBsbibsBSbsbsBibsBSBsbsbibsbSBsBsbibsbSBsBsbiBsbSBsbsbibsbSbsBsBibSbsBsBsb'
    widths=[9 if c in 'BS' else 3 for c in pattern]
    width=sum(widths)+60;height=96
    pixels=bytearray([255])*(width*height*3);x=30
    for c,size in zip(pattern,widths):
        if c in 'bB':
            for y in range(8,height-8): pixels[(y*width+x)*3:(y*width+x+size)*3]=bytes(size*3)
        x+=size
    def chunk(kind,data):return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data)&0xffffffff)
    rows=b''.join(b'\0'+pixels[y*width*3:(y+1)*width*3] for y in range(height))
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',width,height,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(rows))+chunk(b'IEND',b'')


def drawing_frame(name,x,y,w,h,content,rotation=0,fill=None,align='top',padding=(6,3,6,3)):
    emu=lambda v:round(v*12700)
    style=(f'<a:solidFill><a:srgbClr val="{fill}"/></a:solidFill><a:ln w="6350"><a:solidFill><a:srgbClr val="008B90"/></a:solidFill></a:ln>' if fill else '<a:noFill/><a:ln><a:noFill/></a:ln>')
    ident={'code90':10,'code270':11,'text90':12,'text270':13,'diagonal':14}[name]
    return f'''<w:r><w:drawing><wp:anchor distT="0" distB="0" distL="0" distR="0" simplePos="0" relativeHeight="0" behindDoc="0" locked="0" layoutInCell="1" allowOverlap="1">
    <wp:simplePos x="0" y="0"/><wp:positionH relativeFrom="page"><wp:posOffset>{emu(x)}</wp:posOffset></wp:positionH><wp:positionV relativeFrom="page"><wp:posOffset>{emu(y)}</wp:posOffset></wp:positionV>
    <wp:extent cx="{emu(w)}" cy="{emu(h)}"/><wp:effectExtent l="0" t="0" r="0" b="0"/><wp:wrapNone/><wp:docPr id="{ident}" name="{name}"/><wp:cNvGraphicFramePr/>
    <a:graphic><a:graphicData uri="http://schemas.microsoft.com/office/word/2010/wordprocessingShape"><wps:wsp><wps:cNvSpPr txBox="1"/><wps:spPr>
    <a:xfrm rot="{round(rotation*60000)}"><a:off x="0" y="0"/><a:ext cx="{emu(w)}" cy="{emu(h)}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom>{style}</wps:spPr>
    <wps:txbx><w:txbxContent>{content}</w:txbxContent></wps:txbx><wps:bodyPr rot="0" vert="horz" wrap="square" lIns="{emu(padding[0])}" tIns="{emu(padding[1])}" rIns="{emu(padding[2])}" bIns="{emu(padding[3])}" anchor="{'ctr' if align=='middle' else 't'}"><a:noAutofit/></wps:bodyPr>
    </wps:wsp></a:graphicData></a:graphic></wp:anchor></w:drawing></w:r>'''


def build(root, output, kind):
    entries=report_entries(root,output,entries_only=True)
    old=entries['word/document.xml'];prefix=old.split('<w:body>')[0]
    prefix=prefix.replace('<w:document ', '<w:document xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape" xmlns:v="urn:schemas-microsoft-com:vml" xmlns:w10="urn:schemas-microsoft-com:office:word" ')
    section='<w:sectPr>'+old.split('<w:sectPr>')[1].split('</w:sectPr>')[0]+'</w:sectPr>'
    inline=entries['word/header1.xml'].split('<wp:inline',1)[1].split('</wp:inline>',1)[0]
    inline='<wp:inline'+inline+'</wp:inline>'
    titles={'bands':'Afbeeldingen in koptekst en voettekst','frames':'Tekstvakken en tekstomloop','rotated':'Gedraaide tekst en barcodes in de kantlijn'}
    content=paragraph(run(titles[kind]),'<w:pStyle w:val="Title"/>')
    if kind=='bands':
        # Place a second inline image beside the existing footer text in a borderless table.
        header=entries['word/header1.xml'];footer=entries['word/footer1.xml']
        table=header[header.index('<w:tbl>'):header.index('</w:tbl>')+len('</w:tbl>')]
        table=table.replace('r:embed="logo"','r:embed="footerLogo"')
        start=table.index('<w:p><w:pPr>',table.index('</w:tc>'))
        end=table.index('</w:p>',start)+len('</w:p>')
        footer_p=footer[footer.index('<w:p>'):footer.index('</w:p>')+len('</w:p>')]
        table=table[:start]+footer_p+table[end:]
        entries['word/footer1.xml']=footer[:footer.index('<w:p>')]+table+'</w:ftr>'
        entries['word/_rels/footer1.xml.rels']=f'<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="footerLogo" Type="{R}/image" Target="media/footer-logo.png"/></Relationships>'
        entries['word/media/footer-logo.png']=entries['word/media/logo.png']
        for i in range(1,83):content+=p(f'B{i:03d} De afbeeldingen en paginanummers blijven op iedere pagina op hun vaste plaats.',line=360)
    elif kind=='frames':
        content+=p('Vaste tekstvakken, verschillende uitlijningen en een vak dat met de alinea meeloopt.')
        content+=paragraph(frame('left',60,210,225,105,p('Linksboven',bold=True)+p('Dit tekstvak heeft een achtergrond, een rand en eigen binnenmarges. De tekst loopt automatisch om.'),wrap=True),
            '<w:spacing w:before="0" w:after="0" w:line="320" w:lineRule="exact"/>')
        content+=paragraph(frame('right',315,210,220,105,p('Gecentreerd',align='center',bold=True)+p('Tekst in het midden van het vak.',align='center'),align='middle',wrap=True),
            '<w:spacing w:before="0" w:after="0" w:line="320" w:lineRule="exact"/>')
        content+=paragraph(frame('paragraph',60,180,475,75,p('Alineagebonden tekstvak',align='right',bold=True)+p('Deze tekst blijft boven de volgende lopende alinea staan.',align='right'),vertical='text',align='bottom',wrap=True),
            '<w:spacing w:before="0" w:after="0" w:line="320" w:lineRule="exact"/>')
        content+=p('Na het tekstvak gaat de gewone documenttekst verder. Er mag geen overlap of afgekapt woord ontstaan.')
        for i in range(1,19):content+=p(f'T{i:02d} De lopende tekst blijft buiten het alinea-gebonden tekstvak en gaat daaronder verder.')
    else:
        content+=p('De kantlijnen bevatten echte barcodes. Tekst en afbeeldingen draaien samen met het tekstvak.')
        entries['word/media/barcode.png']=barcode_png()
        entries['word/_rels/document.xml.rels']=entries['word/_rels/document.xml.rels'].replace('</Relationships>',f'<Relationship Id="barcode" Type="{R}/image" Target="media/barcode.png"/></Relationships>')
        barcode=paragraph(picture(inline,'barcode',228,24),'<w:spacing w:before="0" w:after="0" w:line="600" w:lineRule="exact"/>')
        shapes=[drawing_frame('code90',-93,353,240,40,barcode,rotation=90),drawing_frame('code270',448,353,240,40,barcode,rotation=270),
            drawing_frame('text90',-53,641,160,24,p('LINKS NEGENTIG GRADEN',size=16,line=240),rotation=90,padding=(3,3,3,3)),
            drawing_frame('text270',488,641,160,24,p('RECHTS TWEEHONDERDZEVENTIG',size=14,line=240),rotation=270,padding=(3,3,3,3)),
            drawing_frame('diagonal',155,400,260,54,p('Tekst onder vijfenveertig graden',align='center',bold=True),rotation=45,align='middle',fill='EAF3F4')]
        for shape in shapes:content+=paragraph(shape,'<w:spacing w:before="0" w:after="0" w:line="320" w:lineRule="exact"/>')
        content+=p('Referentie barcode NF20260057')
    entries['word/document.xml']=prefix+'<w:body>'+content+section+'</w:body></w:document>'
    output.parent.mkdir(parents=True,exist_ok=True)
    with ZipFile(output,'w') as archive:
        for name,value in sorted(entries.items()):
            item=ZipInfo(name,date_time=(2026,10,24,0,0,0));item.create_system=0;item.compress_type=ZIP_DEFLATED
            archive.writestr(item,value.encode('utf-8') if isinstance(value,str) else value)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('output',type=Path);parser.add_argument('--kind',choices=['bands','frames','rotated'],required=True)
    args=parser.parse_args();build(Path(__file__).resolve().parents[3],args.output,args.kind)
