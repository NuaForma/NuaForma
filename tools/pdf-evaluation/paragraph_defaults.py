"""Explicit, opt-in paragraph fallback experiment on trusted template styles.

No Word automation or implicit workstation defaults. Prefix-based XML editing is
research only; do not use this helper as a production upload parser.
"""
from decimal import Decimal
import re
import xml.etree.ElementTree as ET


def apply_defaults(xml, after_points, line_240ths):
    after = Decimal(str(after_points))
    if not after.is_finite() or after < 0 or after > 144:
        raise ValueError('Research after-spacing must be within 0..144 points')
    if not isinstance(line_240ths, int) or isinstance(line_240ths, bool) or not 1 <= line_240ths <= 2400:
        raise ValueError('Research automatic line spacing must be within 1..2400 units')
    twips = after * 20
    if twips != twips.to_integral_value():
        raise ValueError('After-spacing must be representable as whole twips')
    defaults = re.search(r'<w:docDefaults\b[^>]*>.*?</w:docDefaults>', xml, re.S)
    if not defaults:
        raise ValueError('Research requires an explicit docDefaults container')
    content = defaults[0]
    ppr = re.search(r'<w:pPrDefault\b[^>]*(?:/>|>.*?</w:pPrDefault>)', content, re.S)
    block = ppr[0] if ppr else '<w:pPrDefault><w:pPr/></w:pPrDefault>'
    if block.endswith('/>'):
        block = block[:-2] + '><w:pPr/></w:pPrDefault>'
    properties = re.search(r'<w:pPr\b[^>]*(?:/>|>.*?</w:pPr>)', block, re.S)
    if not properties:
        raise ValueError('Malformed paragraph default block')
    old_properties = properties[0]
    expanded = old_properties
    if expanded.endswith('/>'):
        expanded = expanded[:-2] + '></w:pPr>'
    spacing = re.search(r'<w:spacing\b[^>]*(?:/>|>\s*</w:spacing>)', expanded)
    old_spacing = spacing[0] if spacing else '<w:spacing/>'
    additions = {}
    if not re.search(r'\bw:(?:after|afterLines|afterAutospacing)=', old_spacing):
        additions['after'] = str(int(twips))
    # Exact/minimum line spacing belongs to the template and is never recast as auto.
    rule = re.search(r'\bw:lineRule="([^"]+)"', old_spacing)
    if not re.search(r'\bw:line=', old_spacing) and (not rule or rule[1] == 'auto'):
        additions['line'] = str(line_240ths)
        if not rule:
            additions['lineRule'] = 'auto'
    if not additions:
        return xml, []
    new_spacing = old_spacing.replace('<w:spacing', '<w:spacing' + ''.join(f' w:{k}="{v}"' for k, v in additions.items()), 1)
    expanded = expanded.replace(old_spacing, new_spacing, 1) if spacing else expanded.replace('</w:pPr>', new_spacing + '</w:pPr>', 1)
    replacement = block.replace(old_properties, expanded, 1)
    content = content.replace(ppr[0], replacement, 1) if ppr else content.replace('</w:docDefaults>', replacement + '</w:docDefaults>', 1)
    result = xml[:defaults.start()] + content + xml[defaults.end():]
    ET.fromstring(result)
    return result, sorted(additions)
