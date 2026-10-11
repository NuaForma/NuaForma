"""Explicit fallback must preserve every existing template spacing choice."""
import unittest
from paragraph_defaults import apply_defaults

ROOT = '<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults>{}</w:docDefaults><w:style w:styleId="Custom"><w:pPr><w:spacing w:after="400"/></w:pPr></w:style></w:styles>'


class ParagraphDefaultsTest(unittest.TestCase):
    def test_absent_defaults_are_explicit_and_idempotent(self):
        source = ROOT.format('<w:rPrDefault><w:rPr><w:rFonts w:ascii="Private Font"/></w:rPr></w:rPrDefault>')
        result, supplied = apply_defaults(source, 8, 278)
        self.assertEqual(['after', 'line', 'lineRule'], supplied)
        self.assertIn('w:after="160"', result)
        self.assertIn('w:line="278"', result)
        self.assertIn('w:after="400"', result)
        self.assertIn('w:ascii="Private Font"', result)
        self.assertEqual((result, []), apply_defaults(result, 12, 360))

    def test_explicit_spacing_and_nonautomatic_line_rule_win(self):
        for spacing in ('<w:spacing w:after="0" w:line="300" w:lineRule="auto"/>',
                        '<w:spacing w:after="80" w:line="200" w:lineRule="exact"/>',
                        '<w:spacing w:after="80" w:lineRule="atLeast"/>'):
            source = ROOT.format(f'<w:pPrDefault><w:pPr>{spacing}</w:pPr></w:pPrDefault>')
            self.assertEqual((source, []), apply_defaults(source, 8, 278))

    def test_partial_spacing_preserves_supplied_template_attributes(self):
        source = ROOT.format('<w:pPrDefault><w:pPr><w:spacing w:before="60" w:after="0"/></w:pPr></w:pPrDefault>')
        result, supplied = apply_defaults(source, 8, 278)
        self.assertEqual(['line', 'lineRule'], supplied)
        self.assertIn('w:before="60" w:after="0"', result)
        self.assertNotIn('w:after="160"', result)

    def test_alternative_after_spacing_remains_leading(self):
        for attribute in ('w:afterLines="200"', 'w:afterAutospacing="1"', 'w:afterAutospacing="0"'):
            source = ROOT.format(f'<w:pPrDefault><w:pPr><w:spacing {attribute} w:line="278"/></w:pPr></w:pPrDefault>')
            self.assertEqual((source, []), apply_defaults(source, 8, 278))

    def test_existing_line_is_not_reinterpreted(self):
        source = ROOT.format('<w:pPrDefault><w:pPr><w:spacing w:line="360"/></w:pPr></w:pPrDefault>')
        result, supplied = apply_defaults(source, 8, 278)
        self.assertEqual(['after'], supplied)
        self.assertIn('w:line="360"', result)
        self.assertNotIn('w:lineRule=', result)

    def test_empty_containers(self):
        for container in ('<w:pPrDefault/>', '<w:pPrDefault><w:pPr/></w:pPrDefault>'):
            result, supplied = apply_defaults(ROOT.format(container), 0, 240)
            self.assertEqual(['after', 'line', 'lineRule'], supplied)
            self.assertIn('w:after="0"', result)

    def test_invalid_values(self):
        for after, line in ((-1, 278), ('NaN', 278), ('Infinity', 278), (145, 278),
                            (0.001, 278), (8, True), (8, 0), (8, 2401), (8, 278.5)):
            with self.assertRaises(ValueError):
                apply_defaults(ROOT.format(''), after, line)


if __name__ == '__main__':
    unittest.main()
