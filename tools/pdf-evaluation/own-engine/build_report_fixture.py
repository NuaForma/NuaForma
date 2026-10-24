"""Build a repeatable business report with flowing paragraphs, a table, header and PAGE footer."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
from build_fixture import build as letter_entries, paragraph, run, W, R


def build(root, output, entries_only=False):
    entries = letter_entries(root, output, entries_only=True)
    old = entries['word/document.xml']
    prefix = old.split('<w:body>')[0]
    inline = old[old.index('<wp:inline'):old.index('</wp:inline>') + len('</wp:inline>')].replace('685800', '228600')
    logo = '<w:r><w:drawing>' + inline + '</w:drawing></w:r>'
    widths = [900, 5618, 1400, 1720]

    def cell(content, width, align='left', fill=None, line=240):
        shading = f'<w:shd w:val="clear" w:color="auto" w:fill="{fill}"/>' if fill else ''
        return f'<w:tc><w:tcPr><w:tcW w:w="{width}" w:type="dxa"/>{shading}</w:tcPr>' + paragraph(content,
            f'<w:spacing w:before="0" w:after="0" w:line="{line}" w:lineRule="exact"/><w:jc w:val="{align}"/>') + '</w:tc>'

    def table(rows, columns, borders=True):
        edges = ''.join(f'<w:{side} w:val="single" w:sz="4" w:color="B8C5CC"/>' for side in ['top', 'left', 'bottom', 'right', 'insideH', 'insideV']) if borders else ''
        padding = '<w:tblCellMar>' + ''.join(f'<w:{side} w:w="{value}" w:type="dxa"/>' for side, value in [('top',80),('bottom',80),('left',120),('right',120)]) + '</w:tblCellMar>' if borders else '<w:tblCellMar>' + ''.join(f'<w:{s} w:w="0" w:type="dxa"/>' for s in ['top','bottom','left','right']) + '</w:tblCellMar>'
        return f'<w:tbl><w:tblPr><w:tblW w:w="{sum(columns)}" w:type="dxa"/><w:tblInd w:w="{120 if borders else 0}" w:type="dxa"/><w:tblLayout w:type="fixed"/><w:tblBorders>{edges}</w:tblBorders>{padding}</w:tblPr><w:tblGrid>' + ''.join(f'<w:gridCol w:w="{w}"/>' for w in columns) + '</w:tblGrid>' + ''.join(rows) + '</w:tbl>'

    rows = ['<w:tr><w:trPr><w:cantSplit/><w:tblHeader/></w:trPr>' + ''.join(cell(run(t, '<w:b/><w:sz w:val="18"/>'), w, 'right' if i > 1 else 'left', 'DCEFF0') for i,(t,w) in enumerate(zip(['Code','Werkzaamheden','Aantal','Bedrag'],widths))) + '</w:tr>']
    descriptions = [
        'Beoordeling van de aangeleverde documenten en afstemming van de bevindingen met het projectteam.',
        'Uitwerking van de afspraken en controle van de documentopmaak voor de volgende oplevering.',
        'Verwerking van de ontvangen opmerkingen en voorbereiding van de gezamenlijke voortgangsbespreking.',
        'Controle van de vastgelegde gegevens en terugkoppeling over de openstaande aandachtspunten.',
    ]
    for i in range(1,29):
        values = [f'W{i:02d}', descriptions[(i-1)%4], str((i%4)+1), f'€ {120 + i * 15},00']
        rows.append('<w:tr><w:trPr><w:cantSplit/></w:trPr>' + ''.join(cell(run(text, '<w:sz w:val="18"/>'), width, 'right' if col>1 else 'left', 'F3F7F8' if i%2==0 else None) for col,(text,width) in enumerate(zip(values,widths))) + '</w:tr>')
    heading = lambda text: paragraph(run(text), '<w:pStyle w:val="ReportHeading"/>')
    content = [
        paragraph(run('Voortgangsrapport documentvoorziening'), '<w:pStyle w:val="Title"/>'),
        paragraph(run('Rapportperiode oktober 2026', '<w:color w:val="666666"/>')),
        heading('Voortgang en afspraken'),
        paragraph(run('Dit rapport beschrijft de uitgevoerde werkzaamheden en de afspraken voor de volgende periode. Het overzicht ondersteunt de bespreking van de voortgang en maakt zichtbaar welke onderdelen aandacht vragen.')),
        paragraph(run('De documentcontrole is afgerond. ', '<w:b/>') + run('De resultaten zijn vastgelegd en besproken met het team. Nieuwe opmerkingen worden meegenomen in de volgende beoordelingsronde, met behoud van de overeengekomen werkwijze.')),
        paragraph(run('De bedragen in het overzicht zijn indicatief. ', '<w:i/>') + run('De definitieve planning en inzet worden na de gezamenlijke bespreking bevestigd. Alle vermeldingen hebben een eigen referentie, zodat vervolgacties terug te vinden zijn.')),
        heading('Overzicht van de werkzaamheden'), table(rows,widths),
        heading('Bevindingen en vervolg'),
    ]
    conclusions = [
        'De aangeleverde stukken vormen een bruikbare basis voor de volgende fase. De meeste gegevens zijn volledig en sluiten aan op de gemaakte afspraken. Enkele details worden nog met de betrokken medewerkers afgestemd voordat de documenten definitief worden vastgesteld.',
        'Voor de documentopmaak blijven herkenbaarheid en leesbaarheid de uitgangspunten. Het logo, de koppen en de tabelstructuur geven houvast bij het lezen. Bedragen staan consequent rechts uitgelijnd en langere omschrijvingen blijven binnen de afgesproken kolommen.',
        'De komende periode richt het team zich op het verwerken van de resterende opmerkingen. Iedere wijziging krijgt een herleidbare referentie en wordt gecontroleerd op inhoud en presentatie. Daarmee blijft duidelijk welke versie ter beoordeling voorligt en welke afspraken al zijn bevestigd.',
        'Bij de volgende bespreking worden de planning en de beschikbare capaciteit naast elkaar gelegd. De openstaande vragen worden per onderwerp behandeld. Waar extra informatie nodig is, wordt een verantwoordelijke aangewezen en een concreet terugkoppelmoment afgesproken.',
        'De overdracht wordt voorbereid zodra de laatste controle is afgerond. De betrokken medewerkers ontvangen dan de vastgestelde documenten en een overzicht van de gemaakte keuzes. Eventuele bijzonderheden worden toegelicht, zodat de uitvoering zonder onduidelijkheden kan beginnen.',
        'Na de overdracht volgt een korte evaluatie van de werkwijze. Het team verzamelt daarbij ervaringen over de volledigheid, de doorlooptijd en de leesbaarheid van de documenten. De bevindingen worden gebruikt om de volgende rapportage verder te verbeteren.',
    ]
    content += [paragraph(run(text)) for text in conclusions]
    content += [heading('Besluit'), paragraph(run('Het team zet de afgesproken werkzaamheden voort. ', '<w:b/>') + run('De resterende punten worden opgevolgd en tijdens de volgende voortgangsbespreking opnieuw beoordeeld.'))]
    section = f'<w:sectPr><w:headerReference w:type="default" r:id="header"/><w:footerReference w:type="default" r:id="footer"/><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1800" w:right="1134" w:bottom="1200" w:left="1134" w:header="600" w:footer="600" w:gutter="0"/></w:sectPr>'
    entries['word/document.xml'] = prefix + '<w:body>' + ''.join(content) + section + '</w:body></w:document>'
    header_row = '<w:tr><w:trPr><w:cantSplit/></w:trPr>' + cell(logo,2000,line=480) + cell(run('NuaForma  |  Voortgangsrapport','<w:b/><w:sz w:val="18"/>'),7638,'right',line=480) + '</w:tr>'
    entries['word/header1.xml'] = prefix.replace('<w:document','<w:hdr') + table([header_row],[2000,7638],False) + '</w:hdr>'
    footer_text = run('NF-2026-0057  |  Pagina ', '<w:sz w:val="18"/><w:color w:val="666666"/>')
    footer_text += '<w:fldSimple w:instr=" PAGE ">' + run('1','<w:sz w:val="18"/><w:color w:val="666666"/>') + '</w:fldSimple>'
    entries['word/footer1.xml'] = prefix.replace('<w:document','<w:ftr') + paragraph(footer_text, '<w:pStyle w:val="Footer"/>') + '</w:ftr>'
    entries['word/_rels/header1.xml.rels'] = f'<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="logo" Type="{R}/image" Target="media/logo.png"/></Relationships>'
    entries['word/_rels/document.xml.rels'] = entries['word/_rels/document.xml.rels'].replace('</Relationships>', f'<Relationship Id="header" Type="{R}/header" Target="header1.xml"/><Relationship Id="footer" Type="{R}/footer" Target="footer1.xml"/></Relationships>')
    entries['[Content_Types].xml'] = entries['[Content_Types].xml'].replace('</Types>', '<Override PartName="/word/header1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/><Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/></Types>')
    entries['word/styles.xml'] = entries['word/styles.xml'].replace('<w:pPr><w:spacing w:before="0" w:after="160"', '<w:pPr><w:widowControl w:val="0"/><w:spacing w:before="0" w:after="160"').replace('</w:styles>', '<w:style w:type="paragraph" w:styleId="ReportHeading"><w:name w:val="Report Heading"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:before="160" w:after="120" w:line="400" w:lineRule="exact"/></w:pPr><w:rPr><w:b/><w:sz w:val="28"/><w:color w:val="008B90"/></w:rPr></w:style></w:styles>')
    entries['word/styles.xml'] = entries['word/styles.xml'].replace('</w:styles>', '<w:style w:type="paragraph" w:styleId="Footer"><w:name w:val="footer"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:before="0" w:after="0" w:line="240" w:lineRule="exact"/><w:jc w:val="center"/></w:pPr><w:rPr><w:sz w:val="18"/><w:color w:val="666666"/></w:rPr></w:style></w:styles>')
    if entries_only:
        return entries
    output.parent.mkdir(parents=True,exist_ok=True)
    with ZipFile(output,'w') as archive:
        for name,value in sorted(entries.items()):
            info=ZipInfo(name,date_time=(2026,10,24,0,0,0));info.create_system=0;info.compress_type=ZIP_DEFLATED
            archive.writestr(info,value.encode('utf-8') if isinstance(value,str) else value)
    print('Report DOCX written:', output)


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output',type=Path)
    args=parser.parse_args()
    build(Path(__file__).resolve().parents[3],args.output)
