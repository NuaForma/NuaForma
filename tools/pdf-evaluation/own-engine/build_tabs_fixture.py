"""Explicit tab geometry, leaders, decimal symbol and inherited/cleared stops."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
from build_report_fixture import build as report_entries
from build_fixture import W,R,paragraph,run


def tab(pos,kind='left',leader=None):
    return f'<w:tab w:val="{kind}" w:pos="{pos}"'+(f' w:leader="{leader}"' if leader else '')+'/>'


def build(root,output):
    entries=report_entries(root,output,entries_only=True)
    old=entries['word/document.xml'];prefix=old.split('<w:body>')[0]
    section='<w:sectPr>'+old.split('<w:sectPr>')[1].split('</w:sectPr>')[0]+'</w:sectPr>'
    entries['word/settings.xml']=f'<w:settings xmlns:w="{W}"><w:defaultTabStop w:val="720"/><w:decimalSymbol w:val=","/></w:settings>'
    entries['word/_rels/document.xml.rels']=entries['word/_rels/document.xml.rels'].replace('</Relationships>',f'<Relationship Id="settings" Type="{R}/settings" Target="settings.xml"/></Relationships>')
    entries['[Content_Types].xml']=entries['[Content_Types].xml'].replace('</Types>','<Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/></Types>')
    styles='<w:style w:type="paragraph" w:styleId="TabsBase"><w:name w:val="Tabs Base"/><w:basedOn w:val="Normal"/><w:pPr><w:tabs>'+tab(2880)+tab(8640,'right','dot')+'</w:tabs></w:pPr></w:style>'
    styles+='<w:style w:type="paragraph" w:styleId="TabsDerived"><w:name w:val="Tabs Derived"/><w:basedOn w:val="TabsBase"/><w:pPr><w:tabs>'+tab(2880,'clear')+tab(3600)+'</w:tabs></w:pPr></w:style>'
    entries['word/styles.xml']=entries['word/styles.xml'].replace('</w:styles>',styles+'</w:styles>')
    content=[]
    def title(text):content.append(paragraph(run(text),'<w:pStyle w:val="Title"/><w:pageBreakBefore/>'))
    def p(text):content.append(paragraph(run(text)))
    def line(parts,stops='',props='',faces=None):
        runs=''
        for i,text in enumerate(parts):
            if i:runs+='<w:r><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/></w:rPr><w:tab/></w:r>'
            runs+=run(text,(faces or {}).get(i,''))
        content.append(paragraph(runs,props+'<w:spacing w:before="0" w:after="160" w:line="400" w:lineRule="exact"/>'+(f'<w:tabs>{stops}</w:tabs>' if stops else '')))
    title('Tabs en uitlijning')
    p('Tabstops houden gegevens op een vaste positie, zonder handmatig ingevoegde spaties. Deze proef combineert uitlijning, lettertypevarianten en een expliciet decimaalteken.')
    line(['R01 Klant','Voorbeeld B.V.'],tab(8640,'right'))
    line(['R02 Referentie','NF-2026-0057'],tab(8640,'right'))
    line(['L01 Project','Documentvoorziening','Actief'],tab(2880)+tab(6480))
    line(['C01 Status','Ter beoordeling'],tab(5040,'center'))
    for i,value in enumerate(['1.234,56','42,00','7','-123,45'],1):line([f'D{i:02d} Bedrag',value],tab(7920,'decimal'),faces={1:'<w:b/>'} if i==4 else None)
    line(['F01 Opmaak','Vet en cursief'],tab(5760,'right'),faces={0:'<w:i/>',1:'<w:b/>'})
    line(['G01','A','B','C'])
    line(['W01 Vervolg','Deze langere toelichting loopt door over meerdere regels. De tab bepaalt het begin van de eerste tekstregel; de vervolgregels behouden de gewone alinea-inspringing en blijven volledig leesbaar.'],tab(2880))
    title('Opvultekens en overerving')
    p('De opvultekens verbinden een omschrijving met de bijbehorende waarde. Stijlen leveren tabstops; afgeleide stijlen en directe opmaak kunnen ze vervangen of verwijderen.')
    line(['T01 Samenvatting','3'],tab(8640,'right','dot'))
    line(['T02 Afspraken','12'],tab(8640,'right','dot'))
    line(['T03 Volgende controle','27 oktober 2026'],tab(8640,'right','hyphen'))
    line(['T04 Handtekening','Naam'],tab(8640,'right','underscore'))
    line(['T05 Midden','Gecentreerd'],tab(5040,'center','dot'))
    line(['T06 Totaal','9.876,54'],tab(7920,'decimal','dot'))
    line(['T07 Categorie','Waarde','Toelichting'],tab(2880,'left','dot')+tab(8640,'right','hyphen'))
    line(['S01 Basis','Geërfd','Rechts'],props='<w:pStyle w:val="TabsBase"/>')
    line(['S02 Afgeleid','Nieuwe stop','Rechts'],props='<w:pStyle w:val="TabsDerived"/>')
    line(['S03 Direct','Gecentreerd','Rechts'],tab(3600,'clear')+tab(4320,'center')+tab(8640,'right','hyphen'),props='<w:pStyle w:val="TabsDerived"/>')
    content=[x.replace(run('Vet en cursief','<w:b/>'),run('Vet ','<w:b/>')+run('en cursief','<w:i/>')) for x in content]
    entries['word/document.xml']=prefix+'<w:body>'+''.join(content)+section+'</w:body></w:document>'
    output.parent.mkdir(parents=True,exist_ok=True)
    with ZipFile(output,'w') as archive:
        for name,value in sorted(entries.items()):
            info=ZipInfo(name,date_time=(2026,10,24,0,0,0));info.create_system=0;info.compress_type=ZIP_DEFLATED
            archive.writestr(info,value.encode('utf-8') if isinstance(value,str) else value)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('output',type=Path);args=parser.parse_args()
    build(Path(__file__).resolve().parents[3],args.output)
