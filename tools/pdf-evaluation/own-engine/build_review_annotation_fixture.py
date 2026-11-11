"""Build synthetic review-annotation controls; never overwrite reviewed references."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--base', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
word = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
w14 = 'http://schemas.microsoft.com/office/word/2010/wordml'
w15 = 'http://schemas.microsoft.com/office/word/2012/wordml'
cid = 'http://schemas.microsoft.com/office/word/2016/wordml/cid'
cex = 'http://schemas.microsoft.com/office/word/2018/wordml/cex'
office = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
relations = {
    'comments': office + '/comments',
    'commentsExtended': 'http://schemas.microsoft.com/office/2011/relationships/commentsExtended',
    'commentsIds': 'http://schemas.microsoft.com/office/2016/09/relationships/commentsIds',
    'commentsExtensible': 'http://schemas.microsoft.com/office/2018/08/relationships/commentsExtensible',
    'people': 'http://schemas.microsoft.com/office/2011/relationships/people',
}


def comment(identity, paragraph, content):
    return (f'<w:comment w:id="{identity}" w:author="Fixture Reviewer" w:initials="FR" w:date="2026-11-09T12:00:00Z">'
            f'<w:p w14:paraId="{paragraph}"><w:r><w:rPr><w:rFonts w:ascii="Missing Comment Font" '
            'w:hAnsi="Missing Comment Font"/></w:rPr><w:t xml:space="preserve">' + content + '</w:t></w:r></w:p></w:comment>')


def reference(identity):
    return ('<w:r><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/>'
            f'<w:sz w:val="16"/><w:vertAlign w:val="superscript"/></w:rPr><w:commentReference w:id="{identity}"/></w:r>')


for paper in ['a4', 'letter']:
    with ZipFile(args.base / paper / 'source.docx') as archive:
        original = {name: archive.read(name) for name in archive.namelist()}
    document = original['word/document.xml'].decode()
    assert '<w:r>' in document and '</w:p></w:tc>' in document
    document = document.replace('<w:r>', '<w:commentRangeStart w:id="0"/><w:r>', 1)
    document = document.replace('</w:r>', '</w:r><w:commentRangeEnd w:id="0"/>' + reference(0), 1)
    document = document.replace('</w:p></w:tc>', reference(2) + '</w:p></w:tc>', 1)
    comments = (f'<w:comments xmlns:w="{word}" xmlns:w14="{w14}">' +
        comment(0, '10000001', '{UNMATCHED_COMMENT_MARKER') +
        comment(1, '10000002', 'Reply contains {unknown | unsupportedPipe()} but is not printed') +
        comment(2, '10000003', 'Table point comment: العربية Кириллица') + '</w:comments>')
    extended = (f'<w15:commentsEx xmlns:w15="{w15}"><w15:commentEx w15:paraId="10000001" w15:done="0"/>'
        '<w15:commentEx w15:paraId="10000002" w15:paraIdParent="10000001"/>'
        '<w15:commentEx w15:paraId="10000003" w15:done="1"/></w15:commentsEx>')
    identities = f'<w16cid:commentsIds xmlns:w16cid="{cid}">' + ''.join(
        f'<w16cid:commentId w16cid:paraId="1000000{i}" w16cid:durableId="2000000{i}"/>' for i in range(1, 4)) + '</w16cid:commentsIds>'
    extensible = f'<w16cex:commentsExtensible xmlns:w16cex="{cex}">' + ''.join(
        f'<w16cex:commentExtensible w16cex:durableId="2000000{i}" w16cex:dateUtc="2026-11-09T12:00:00Z"/>'
        for i in range(1, 4)) + '</w16cex:commentsExtensible>'
    people = f'<w15:people xmlns:w15="{w15}"><w15:person w15:author="Fixture Reviewer"/></w15:people>'
    for variant in ['classic', 'modern', 'stale-modern', 'reference-style']:
        parts = original.copy()
        parts['word/document.xml'] = document.encode()
        additions = {'comments': comments}
        if variant != 'classic':
            additions.update(commentsExtended=extended, commentsIds=identities, commentsExtensible=extensible, people=people)
        if variant == 'stale-modern':
            parts['word/document.xml'] = original['word/document.xml']
            additions['comments'] = f'<w:comments xmlns:w="{word}"/>'
            additions['people'] = (f'<w15:people xmlns:w15="{w15}"><w15:person w15:author="Fixture Reviewer">'
                '<w15:presenceInfo w15:providerId="None" w15:userId="fixture-local"/></w15:person></w15:people>')
        if variant == 'reference-style':
            for identity in [0, 2]:
                parts['word/document.xml'] = parts['word/document.xml'].replace(reference(identity).encode(),
                    reference(identity).replace('<w:rPr>', '<w:rPr><w:rStyle w:val="ReviewReference"/>').encode())
            definition = ('<w:style w:type="character" w:styleId="ReviewReference"><w:name w:val="Review reference"/>'
                '<w:rPr><w:rFonts w:ascii="Missing Reference Font" w:hAnsi="Missing Reference Font"/>'
                '<w:highlight w:val="yellow"/></w:rPr></w:style>')
            for name in ['word/styles.xml', 'word/stylesWithEffects.xml']:
                if name in parts:
                    parts[name] = parts[name].decode().replace('</w:styles>', definition + '</w:styles>').encode()
        links = original['word/_rels/document.xml.rels'].decode()
        types = original['[Content_Types].xml'].decode()
        for index, (name, content) in enumerate(additions.items()):
            parts['word/' + name + '.xml'] = content.encode()
            links = links.replace('</Relationships>',
                f'<Relationship Id="review{index}" Type="{relations[name]}" Target="{name}.xml"/></Relationships>')
            types = types.replace('</Types>', f'<Override PartName="/word/{name}.xml" '
                f'ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.{name}+xml"/></Types>')
        parts['word/_rels/document.xml.rels'] = links.encode()
        parts['[Content_Types].xml'] = types.encode()
        folder = args.output / variant / paper
        folder.mkdir(parents=True, exist_ok=False)
        with ZipFile(folder / 'source.docx', 'x', ZIP_DEFLATED) as archive:
            for name, data in parts.items():
                entry = ZipInfo(name, (2025, 1, 1, 0, 0, 0))
                entry.create_system = 0
                entry.compress_type = ZIP_DEFLATED
                archive.writestr(entry, data)
print('Eight synthetic review-annotation sources created.')
