"""Real OOXML numbering, style-linked chapters and hanging indents for Word comparison."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
from xml.sax.saxutils import escape
from build_report_fixture import build as report_entries
from build_fixture import paragraph, run, W, R


def level(index, fmt='decimal', pattern=None, start=1, left=None, hanging=360, align='left', suffix='tab', extra='', face=''):
    left=left if left is not None else (index+1)*720
    pattern=pattern if pattern is not None else f'%{index+1}.'
    return f'<w:lvl w:ilvl="{index}"><w:start w:val="{start}"/><w:numFmt w:val="{fmt}"/>{extra}<w:suff w:val="{suffix}"/><w:lvlText w:val="{escape(pattern)}"/><w:lvlJc w:val="{align}"/><w:pPr><w:tabs><w:tab w:val="num" w:pos="{left}"/></w:tabs><w:ind w:left="{left}" w:hanging="{hanging}"/></w:pPr><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/>{face}</w:rPr></w:lvl>'


def build(root, output):
    entries=report_entries(root,output,entries_only=True)
    old=entries['word/document.xml'];prefix=old.split('<w:body>')[0]
    section='<w:sectPr>'+old.split('<w:sectPr>')[1].split('</w:sectPr>')[0]+'</w:sectPr>'
    abstracts={
        1: ''.join(level(i,'bullet',symbol,face='<w:color w:val="16365D"/>') for i,symbol in enumerate(['•','–','•'])),
        2: level(0,align='right'),
        3: level(0,'lowerLetter','%1)',start=25,align='right'),
        4: level(0,'upperRoman','%1.',start=8,align='center',left=900,hanging=450),
        5: level(0,'decimal','(%1)',suffix='space',face='<w:b/><w:color w:val="16365D"/>'),
        6: level(0,'upperLetter','%1)',suffix='nothing'),
        7: ''.join(level(i,'decimal','.'.join(f'%{n+1}' for n in range(i+1)),left=900+i*720,hanging=720,
                 extra=f'<w:pStyle w:val="Chapter{i+1}"/>',face='<w:b/>') for i in range(3)),
        8: level(0,start=98,align='right',left=1080,hanging=360),
        9: level(0)+level(1,'lowerLetter','%1.%2)')+level(2,'lowerRoman','%3)',extra='<w:lvlRestart w:val="0"/>'),
        10: level(0,'decimalZero','%1.',align='right'),
        11: level(0,'upperRoman','%1.')+level(1,'lowerLetter','%1.%2',extra='<w:isLgl/>'),
    }
    instances={1:1,2:2,3:3,4:4,5:5,6:6,7:7,8:8,9:9,10:2,11:10,12:2,13:11,14:7}
    overrides={10:'<w:lvlOverride w:ilvl="0"><w:startOverride w:val="7"/></w:lvlOverride>',
               11:'<w:lvlOverride w:ilvl="0"><w:startOverride w:val="3"/></w:lvlOverride>',
               14:'<w:lvlOverride w:ilvl="0"><w:startOverride w:val="4"/></w:lvlOverride>',
               12:'<w:lvlOverride w:ilvl="0"><w:startOverride w:val="0"/></w:lvlOverride>'}
    numbering=f'<w:numbering xmlns:w="{W}">'+''.join(f'<w:abstractNum w:abstractNumId="{key}"><w:multiLevelType w:val="multilevel"/>{value}</w:abstractNum>' for key,value in abstracts.items())
    numbering+=''.join(f'<w:num w:numId="{key}"><w:abstractNumId w:val="{value}"/>{overrides.get(key,"")}</w:num>' for key,value in instances.items())+'</w:numbering>'
    entries['word/settings.xml']=f'<w:settings xmlns:w="{W}"><w:defaultTabStop w:val="720"/></w:settings>'
    entries['word/_rels/document.xml.rels']=entries['word/_rels/document.xml.rels'].replace('</Relationships>',f'<Relationship Id="settings" Type="{R}/settings" Target="settings.xml"/></Relationships>')
    entries['[Content_Types].xml']=entries['[Content_Types].xml'].replace('</Types>','<Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/></Types>')
    entries['word/numbering.xml']=numbering
    entries['word/_rels/document.xml.rels']=entries['word/_rels/document.xml.rels'].replace('</Relationships>',f'<Relationship Id="numbering" Type="{R}/numbering" Target="numbering.xml"/></Relationships>')
    entries['[Content_Types].xml']=entries['[Content_Types].xml'].replace('</Types>','<Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/></Types>')
    styles=''
    for i in range(1,4):
        styles+=f'<w:style w:type="paragraph" w:styleId="Chapter{i}"><w:name w:val="Chapter {i}"/><w:basedOn w:val="Normal"/><w:pPr><w:keepNext/><w:keepLines/><w:outlineLvl w:val="{i-1}"/><w:numPr><w:ilvl w:val="{i-1}"/><w:numId w:val="7"/></w:numPr><w:spacing w:before="160" w:after="100" w:line="{440-i*40}" w:lineRule="exact"/></w:pPr><w:rPr><w:b/><w:color w:val="000000"/><w:sz w:val="{30-i*2}"/></w:rPr></w:style>'
    styles+='<w:style w:type="paragraph" w:styleId="InheritedChapter"><w:name w:val="Inherited Chapter"/><w:basedOn w:val="Chapter2"/></w:style>'
    entries['word/styles.xml']=entries['word/styles.xml'].replace('</w:styles>',styles+'</w:styles>')
    body=[]
    def title(text):body.append(paragraph(run(text),'<w:pStyle w:val="Title"/><w:pageBreakBefore/>'))
    def text(value,props='',face=''):body.append(paragraph(run(value,face),'<w:spacing w:before="0" w:after="120" w:line="360" w:lineRule="exact"/>'+props))
    def item(marker,value,num=1,lvl=0,props='',face=''):
        text(marker+' '+value,f'<w:numPr><w:ilvl w:val="{lvl}"/><w:numId w:val="{num}"/></w:numPr>'+props,face)
    title('Opsommingen en inspringing')
    text('Een praktijkproef met echte lijstdefinities, eigen labelopmaak en verschillende inspringingen. De vervolgregels moeten onder de tekst beginnen, terwijl het lijstteken links blijft staan.')
    long='De documenten worden beoordeeld op inhoud, volledigheid en opmaak. De bevindingen worden met de verantwoordelijke collega besproken, zodat duidelijk is welke controle en opvolging nodig zijn.'
    for marker,lvl,value in [('B01',0,'Voorbereiding en afstemming. '+long),('B02',1,'Controle van de ontvangen gegevens. '+long),('B03',2,'Detailcontrole van de bijlagen en verwijzingen. '+long),('B04',1,'Terugkoppeling van de bevindingen.'),('B05',0,'Afronding en vastlegging van de afspraken. '+long)]:
        item(marker,value,lvl=lvl,face='<w:i/>' if marker=='B02' else '')
    item('B06','Een afwijkende hangende inspringing: de eerste tekstregel en de vervolgregels staan op dezelfde positie. '+long,props='<w:ind w:left="1440" w:hanging="720"/><w:tabs><w:tab w:val="num" w:pos="1440"/></w:tabs>')
    text('H01 Een gewone alinea met hangende inspringing. '+long,'<w:ind w:left="720" w:hanging="360"/>')
    text('H02 Een gewone alinea met een ingesprongen eerste regel. '+long,'<w:ind w:left="360" w:firstLine="360"/>')
    title('Nummering, herstarts en uitlijning')
    for n in range(1,13):item(f'D{n:02d}','Een rechts uitgelijnd nummer met een vaste tekstpositie.',num=2)
    text('Een onderbreking zonder lijstnummer verandert de volgende teller niet.')
    item('D13','Dezelfde lijst loopt door na een gewone alinea.',num=2)
    for n in range(1,4):item(f'R{n:02d}','Een nieuwe lijstinstantie start bij zeven.',num=10)
    item('D14','De oorspronkelijke lijst loopt verder na een andere lijstinstantie.',num=2)
    for n in range(1,4):item(f'Z{n:02d}','Een lijst met voorloopnullen en een eigen startwaarde.',num=11)
    item('Z00','Een expliciete startwaarde nul blijft behouden.',num=12)
    title('Letters, Romeinse cijfers en achtervoegsels')
    for n in range(1,5):item(f'A{n:02d}','Alfabetische nummering rond de grens van z naar dubbele letters.',num=3)
    for n in range(1,5):item(f'V{n:02d}','Een gecentreerd Romeins label met een ruimere hangende inspringing.',num=4)
    item('S01','Een vet donkerblauw label met een spatie als scheiding. '+long,num=5)
    item('S02','Het volgende item gebruikt dezelfde labelopmaak.',num=5)
    item('N01','Geen scheiding tussen het label en de eerste letter. '+long,num=6)
    for n,(lvl,value) in enumerate([(0,'Hoofdniveau'),(1,'Tweede niveau'),(2,'Derde niveau'),(2,'Nogmaals derde niveau'),(0,'Nieuw hoofdniveau'),(1,'Tweede niveau herstart'),(2,'Derde niveau blijft doortellen')],1):
        item(f'M{n:02d}',value,num=9,lvl=lvl)
    for n,lvl in enumerate([0,1,1],1):item(f'L{n:02d}','Juridische nummering gebruikt decimale voorvoegsels.',num=13,lvl=lvl)
    title('Genummerde hoofdstukken')
    for marker,style,value in [('C01','Chapter1','Doel en uitgangspunten'),('C02','Chapter2','Werkwijze'),('C03','Chapter3','Voorbereiding'),('C04','Chapter3','Beoordeling'),('C05','InheritedChapter','Vastlegging'),('C06','Chapter3','Controle van de resultaten'),('C07','Chapter1','Uitvoering en opvolging'),('C08','Chapter2','Verantwoordelijkheden'),('C09','Chapter3','Overdracht')]:
        body.append(paragraph(run(marker+' '+value),f'<w:pStyle w:val="{style}"/>'))
        text('De afspraak wordt vastgelegd en door de verantwoordelijke collega opgevolgd. Deze tekst hoort bij de genummerde kop en houdt zijn eigen opmaak.')
    text('C10 Nummering kan voor een enkele alinea expliciet worden uitgezet.','<w:pStyle w:val="Chapter2"/><w:numPr><w:numId w:val="0"/></w:numPr>')
    for marker,style,value in [('C11','Chapter1','Een herstart bij hoofdstuk vier'),('C12','Chapter2','De nieuwe onderverdeling')]:
        body.append(paragraph(run(marker+' '+value),f'<w:pStyle w:val="{style}"/><w:numPr><w:numId w:val="14"/></w:numPr>'))
    text('Deze nieuwe hoofdstukreeks heeft een eigen beginwaarde en behoudt de onderliggende niveaus.')
    title('Doorlopende lijsten over pagina’s')
    for n in range(1,25):item(f'P{n:02d}',long+' Bij de paginaovergang blijven label, eerste regel en vervolgregels aan hetzelfde lijstitem gekoppeld.',num=8,props='<w:widowControl/>')
    entries['word/document.xml']=prefix+'<w:body>'+''.join(body)+section+'</w:body></w:document>'
    output.parent.mkdir(parents=True,exist_ok=True)
    with ZipFile(output,'w') as archive:
        for name,value in sorted(entries.items()):
            info=ZipInfo(name,date_time=(2026,10,24,0,0,0));info.create_system=0;info.compress_type=ZIP_DEFLATED
            archive.writestr(info,value.encode('utf-8') if isinstance(value,str) else value)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('output',type=Path);args=parser.parse_args()
    build(Path(__file__).resolve().parents[3],args.output)
