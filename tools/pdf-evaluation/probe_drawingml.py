"""Trusted native-text DrawingML diagnostic copies, not a production normalizer."""
import argparse
import copy
import hashlib
import json
import pathlib
import re
import zipfile
from decimal import Decimal

parser = argparse.ArgumentParser()
parser.add_argument('source', type=pathlib.Path)
parser.add_argument('directory', type=pathlib.Path)
args = parser.parse_args()
before = hashlib.sha256(args.source.read_bytes()).hexdigest()
args.directory.mkdir(parents=True, exist_ok=False)
with zipfile.ZipFile(args.source) as source:
    original = source.read('word/document.xml').decode()
    match = re.search(r'<w:pict\b[^>]*>\s*<v:shape\b[^>]*id="MarginBox".*?</v:shape>\s*</w:pict>', original, re.S)
    assert match, 'Expected the synthetic margin fixture'
    shape = match.group()
    style = re.search(r'<v:shape[^>]*style="([^"]+)"', shape)[1]
    css = dict(item.split(':', 1) for item in style.split(';') if ':' in item)
    width, height = (int(Decimal(css[k][:-2]) * 12700) for k in ('height', 'width'))
    text = re.search(r'<w:txbxContent>.*?</w:txbxContent>', shape, re.S)[0]
    variants = {
        'vertical': ('rightMargin', '<wp:align>left</wp:align>', 'vert="vert270"', 't'),
        'page-right': ('page', '<wp:align>right</wp:align>', 'vert="vert270"', 't'),
        'rotate-text': ('rightMargin', '<wp:align>left</wp:align>', 'rot="16200000" vert="horz"', 't'),
        'vertical-bottom': ('rightMargin', '<wp:align>left</wp:align>', 'vert="vert270"', 'b'),
        'vertical-center': ('rightMargin', '<wp:align>left</wp:align>', 'vert="vert270"', 'ctr'),
    }
    records = []
    for name, (relative, position, flow, anchor) in variants.items():
        drawing = f'''<w:drawing><wp:anchor distT="0" distB="0" distL="0" distR="0" simplePos="0" relativeHeight="251657728" behindDoc="0" locked="0" layoutInCell="1" allowOverlap="1">
          <wp:simplePos x="0" y="0"/><wp:positionH relativeFrom="{relative}">{position}</wp:positionH>
          <wp:positionV relativeFrom="margin"><wp:align>bottom</wp:align></wp:positionV>
          <wp:extent cx="{width}" cy="{height}"/><wp:effectExtent l="0" t="0" r="0" b="0"/><wp:wrapNone/>
          <wp:docPr id="42" name="MarginBox"/><wp:cNvGraphicFramePr/>
          <a:graphic><a:graphicData uri="http://schemas.microsoft.com/office/word/2010/wordprocessingShape">
          <wps:wsp><wps:cNvSpPr txBox="1"/><wps:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="{width}" cy="{height}"/></a:xfrm>
          <a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill>
          <a:ln w="9525"><a:solidFill><a:srgbClr val="000000"/></a:solidFill><a:prstDash val="solid"/></a:ln></wps:spPr>
          <wps:txbx>{text}</wps:txbx><wps:bodyPr {flow} wrap="none" lIns="0" tIns="0" rIns="0" bIns="0" anchor="{anchor}" anchorCtr="0"><a:noAutofit/></wps:bodyPr>
          </wps:wsp></a:graphicData></a:graphic></wp:anchor></w:drawing>'''
        patched = original[:match.start()] + drawing + original[match.end():]
        with zipfile.ZipFile(args.directory / f'{name}.docx', 'x') as result:
            for entry in source.infolist():
                result.writestr(copy.copy(entry), patched.encode() if entry.filename == 'word/document.xml' else source.read(entry))
        records.append(dict(variant=name, sourceSha256=before, changedParts=['word/document.xml']))
assert hashlib.sha256(args.source.read_bytes()).hexdigest() == before
(args.directory / 'probes.json').write_text(json.dumps(records, indent=2))
print(json.dumps(records))
