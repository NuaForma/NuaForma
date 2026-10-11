"""Narrow native-text geometry research on trusted synthetic conversion copies.

Not a production importer. Only the explicitly measured right-margin profile is
handled; other alignment/section models must not be inferred from this experiment.
"""
import argparse
import copy
from decimal import Decimal
import hashlib
import json
import pathlib
import re
import zipfile
import xml.etree.ElementTree as ET


def emu(value):
    if value == '0':
        return 0
    if not re.fullmatch(r'[0-9]+(?:\.[0-9]+)?pt', value):
        raise ValueError('Research requires explicit nonnegative point lengths')
    return int(Decimal(value[:-2]) * 12700)


def normalize(xml, include_inline=False):
    sections = re.findall(r'<w:pgMar\b[^>]*\bw:right="(\d+)"', xml)
    if not sections:
        raise ValueError('Research requires explicit section right margins')
    margin = min(int(s) for s in sections) * 635
    next_id = max([0] + [int(s) for s in re.findall(r'<wp:docPr\b[^>]*\bid="(\d+)"', xml)])
    count = 0

    def replace(match):
        nonlocal count, next_id
        source = match.group()
        shape = re.search(r'<v:shape\b([^>]*)>(.*?)</v:shape>', source, re.S)
        if not shape or 'type="#_x0000_t202"' not in shape[1]:
            return source
        style_match = re.search(r'\bstyle="([^"]+)"', shape[1])
        if not style_match:
            return source
        css = dict(item.split(':', 1) for item in style_match[1].split(';') if ':' in item)
        if 'mso-layout-flow-alt:bottom-to-top' not in shape[2]:
            if not include_inline or set(css) != {'width', 'height'}:
                return source
            if re.sub(r'\b(?:id|style|type)="[^"]*"', '', shape[1]).strip():
                raise ValueError('Unmeasured inline shape attributes')
            width, height = emu(css['width']), emu(css['height'])
            if min(width, height) <= 0:
                raise ValueError('Positive inline dimensions required')
            textbox = re.search(r'<v:textbox\s+inset="([^"]+)"\s*>\s*(<w:txbxContent>.*?</w:txbxContent>)\s*</v:textbox>', shape[2], re.S)
            if not textbox or shape[2].replace(textbox[0], '').strip():
                raise ValueError('Unsupported inline textbox')
            insets = [emu(s.strip()) for s in textbox[1].split(',')]
            if len(insets) != 4:
                raise ValueError('Four explicit insets required')
            left, top, right, bottom = insets
            next_id += 1
            count += 1
            return f'''<w:drawing xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape"><wp:inline distT="0" distB="0" distL="0" distR="0">
              <wp:extent cx="{width}" cy="{height}"/><wp:effectExtent l="0" t="0" r="0" b="0"/><wp:docPr id="{next_id}" name="NF conversion inline box"/><wp:cNvGraphicFramePr/>
              <a:graphic><a:graphicData uri="http://schemas.microsoft.com/office/word/2010/wordprocessingShape"><wps:wsp><wps:cNvSpPr txBox="1"/>
              <wps:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="{width}" cy="{height}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom>
              <a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill><a:ln w="9525"><a:solidFill><a:srgbClr val="000000"/></a:solidFill></a:ln></wps:spPr>
              <wps:txbx>{textbox[2]}</wps:txbx><wps:bodyPr vert="horz" wrap="square" lIns="{left}" tIns="{top}" rIns="{right}" bIns="{bottom}" anchor="t"><a:noAutofit/></wps:bodyPr>
              </wps:wsp></a:graphicData></a:graphic></wp:inline></w:drawing>'''
        required = {
            'position': 'absolute', 'rotation': '90',
            'mso-position-horizontal': 'right', 'mso-position-horizontal-relative': 'page',
            'mso-position-vertical': 'bottom', 'mso-position-vertical-relative': 'margin',
        }
        if any(css.get(k) != v for k, v in required.items()):
            raise ValueError('Unsupported matched margin-box alignment profile')
        unknown = set(css) - set(required) - {'width', 'height', 'left', 'top', 'margin-left', 'margin-top'}
        if unknown:
            raise ValueError(f'Unmeasured shape properties: {sorted(unknown)}')
        if re.sub(r'\b(?:id|style|type)="[^"]*"', '', shape[1]).strip():
            raise ValueError('Unmeasured shape attributes')
        width, height = emu(css['height']), emu(css['width'])
        if min(width, height) <= 0 or width > margin:
            raise ValueError('Research requires positive bounds fitting the right margin')
        textbox = re.search(r'<v:textbox\b([^>]*)>\s*(<w:txbxContent>.*?</w:txbxContent>)\s*</v:textbox>', shape[2], re.S)
        if not textbox or re.sub(r'\b(?:inset|style)="[^"]*"', '', textbox[1]).strip():
            raise ValueError('Unsupported textbox content')
        textbox_style = re.search(r'\bstyle="([^"]+)"', textbox[1])
        if not textbox_style or textbox_style[1] != 'mso-layout-flow-alt:bottom-to-top':
            raise ValueError('Unmeasured textbox flow properties')
        inset = re.search(r'\binset="([^"]+)"', textbox[1])
        if not inset or len(inset[1].split(',')) != 4 or any(emu(s.strip()) != 0 for s in inset[1].split(',')):
            raise ValueError('Only the measured zero-inset profile is supported')
        if shape[2].replace(textbox[0], '').strip():
            raise ValueError('Unmeasured shape child')
        next_id += 1
        count += 1
        # No fixture-specific coordinates. Anchor the rotated bound at the section's
        # right-margin start; OOXML/renderer resolve each page and section geometry.
        # Keep native text, run properties, fonts and paragraph content verbatim.
        return f'''<w:drawing xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape"><wp:anchor distT="0" distB="0" distL="0" distR="0" simplePos="0" relativeHeight="251657728" behindDoc="0" locked="0" layoutInCell="1" allowOverlap="1">
          <wp:simplePos x="0" y="0"/><wp:positionH relativeFrom="rightMargin"><wp:align>left</wp:align></wp:positionH>
          <wp:positionV relativeFrom="margin"><wp:align>bottom</wp:align></wp:positionV>
          <wp:extent cx="{width}" cy="{height}"/><wp:effectExtent l="0" t="0" r="0" b="0"/><wp:wrapNone/>
          <wp:docPr id="{next_id}" name="NF conversion margin box"/><wp:cNvGraphicFramePr/>
          <a:graphic><a:graphicData uri="http://schemas.microsoft.com/office/word/2010/wordprocessingShape">
          <wps:wsp><wps:cNvSpPr txBox="1"/><wps:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="{width}" cy="{height}"/></a:xfrm>
          <a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill>
          <a:ln w="9525"><a:solidFill><a:srgbClr val="000000"/></a:solidFill><a:prstDash val="solid"/></a:ln></wps:spPr>
          <wps:txbx>{textbox[2]}</wps:txbx><wps:bodyPr vert="vert270" wrap="none" lIns="0" tIns="0" rIns="0" bIns="0" anchor="t" anchorCtr="0"><a:noAutofit/></wps:bodyPr>
          </wps:wsp></a:graphicData></a:graphic></wp:anchor></w:drawing>'''

    patched = re.sub(r'<w:pict\b[^>]*>\s*<v:shape\b.*?</v:shape>\s*</w:pict>', replace, xml, flags=re.S)
    return patched, count


def conversion_copy(source, destination, include_inline=False):
    before = hashlib.sha256(source.read_bytes()).hexdigest()
    changed = []
    count = 0
    with zipfile.ZipFile(source) as package:
        patched, count = normalize(package.read('word/document.xml').decode(), include_inline)
        ET.fromstring(patched)
        if count:
            changed.append('word/document.xml')
    with zipfile.ZipFile(source) as package, zipfile.ZipFile(destination, 'x') as result:
        for entry in package.infolist():
            content = package.read(entry)
            if entry.filename == 'word/document.xml':
                if count:
                    content = patched.encode()
            result.writestr(copy.copy(entry), content)
    with zipfile.ZipFile(source) as package, zipfile.ZipFile(destination) as result:
        assert package.namelist() == result.namelist()
        assert all(package.read(n) == result.read(n) for n in package.namelist() if n not in changed)
    assert hashlib.sha256(source.read_bytes()).hexdigest() == before
    return dict(source=str(source), sourceSha256=before, changedParts=changed, normalizedShapes=count)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('source', type=pathlib.Path)
    parser.add_argument('destination', type=pathlib.Path)
    args = parser.parse_args()
    print(json.dumps(conversion_copy(args.source, args.destination)))
