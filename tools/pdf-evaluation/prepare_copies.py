"""Build conversion-only experiment copies of the existing synthetic QA matrix."""
import argparse
import json
import pathlib

from conversion_copy import conversion_copy

FIXTURES = {
    'roundtrip-before': 'docx-reference/before',
    'roundtrip-after': 'docx-reference/after',
    'complex-expected': 'docx-generation-reference/complex-expected',
    'complex-generated': 'docx-generation-reference/complex-generated',
    'barcode-expected': 'font-reference/expected',
    'barcode-generated': 'font-reference/generated',
    'barcode-embedded': 'font-reference/embedded',
    'barcode-isolated': 'font-reference/isolated-embedded',
    **{f'watermark-{name}': f'watermark-reference/{name}' for name in
       ('plain', 'concept', 'dynamic', 'vertical', 'transparent', 'opaque')},
}

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('repository', type=pathlib.Path)
    parser.add_argument('destination', type=pathlib.Path)
    parser.add_argument('--strategy', choices=('dimensions', 'drawingml', 'drawingml-inline'), default='dimensions')
    args = parser.parse_args()
    if args.strategy.startswith('drawingml'):
        from drawingml_copy import conversion_copy
        if args.strategy == 'drawingml-inline':
            from functools import partial
            conversion_copy = partial(conversion_copy, include_inline=True)
    args.destination.mkdir(parents=True, exist_ok=False)
    records = []
    for name, path in FIXTURES.items():
        record = conversion_copy(args.repository / 'nuaforma-docx/build' / f'{path}.docx',
                                 args.destination / f'{name}.docx')
        record['fixture'] = name
        records.append(record)
    (args.destination / 'copies.json').write_text(json.dumps(records, indent=2), encoding='utf-8')
    print(json.dumps(records))
