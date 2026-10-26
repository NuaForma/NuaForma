"""Compare the actual Word export and own-engine PDF without image alignment or rescaling."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
from PIL import Image, ImageChops, ImageDraw
from pypdf import PdfReader


def normalized(text):
    return re.sub(r'\s+', ' ', text).strip()


def inspect(path):
    pdf = PdfReader(path)
    pages = []
    for page in pdf.pages:
        spans = []
        images = []
        def operation(op, operands, cm, tm):
            if op == b'Do':
                obj = page['/Resources']['/XObject'][operands[0]].get_object()
                if obj.get('/Subtype') == '/Image':
                    images.append(dict(matrix=[float(value) for value in cm],
                                       pixelWidth=int(obj['/Width']), pixelHeight=int(obj['/Height'])))
        def visit(text, cm, tm, font, size):
            if text.strip('\r\n'):
                spans.append(dict(text=text.strip('\r\n'), x=float(tm[4]), y=float(tm[5]), size=float(size), font=str(font.get('/BaseFont')) if font else None))
        text = page.extract_text(visitor_text=visit, visitor_operand_before=operation)
        lines = []
        for span in spans:
            existing = next((line for line in lines if abs(line['y'] - span['y']) < 0.2), None)
            if existing is None:
                existing = dict(y=span['y'], spans=[])
                lines.append(existing)
            existing['spans'].append(span)
        for line in lines:
            line['spans'].sort(key=lambda item: item['x'])
            line['text'] = normalized(''.join(item['text'] for item in line['spans']))
            line['x'] = min(item['x'] for item in line['spans'])
        lines = [line for line in lines if line['text']]
        pages.append(dict(width=float(page.mediabox.width), height=float(page.mediabox.height),
                          text=normalized(text), lines=lines, images=images))
    return dict(sha256=hashlib.sha256(path.read_bytes()).hexdigest(), pageCount=len(pages), pages=pages)


def geometric_lines(page):
    # Matrix baselines avoid associating buffered text with a later graphics-state restore.
    lines = []
    for char in page.chars:
        baseline = float(char['matrix'][5])
        line = next((line for line in lines if abs(line['y'] - baseline) < 0.2), None)
        if line is None:
            line = dict(y=baseline, chars=[])
            lines.append(line)
        line['chars'].append(char)
    result = []
    for line in sorted(lines, key=lambda item: -item['y']):
        chars = sorted(line['chars'], key=lambda item: item['x0'])
        text = ''
        previous_end = None
        for char in chars:
            if previous_end is not None and char['x0'] - previous_end > max(0.5, char['size'] * 0.2) and not text.endswith(' ') and char['text'] != ' ':
                text += ' '
            text += char['text']
            previous_end = char['x1']
        if normalized(text):
            visible = [char for char in chars if char['text'].strip()]
            result.append(dict(y=line['y'], x=visible[0]['x0'], text=normalized(text)))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('reference', type=Path)
    parser.add_argument('candidate', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--poppler-dir', type=Path)
    parser.add_argument('--source', type=Path)
    parser.add_argument('--font-dir', type=Path)
    parser.add_argument('--word-bounds', action='store_true', help='Also compare word rectangles with pdfplumber')
    parser.add_argument('--allow-content-differences', action='store_true', help='Record intentional text differences without asserting equality; page counts must still match')
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    tool = str(args.poppler_dir / ('pdftoppm.exe' if (args.poppler_dir / 'pdftoppm.exe').exists() else 'pdftoppm')) if args.poppler_dir else shutil.which('pdftoppm')
    if not tool:
        raise RuntimeError('Poppler pdftoppm is required for independent rendering')
    reference, candidate = inspect(args.reference), inspect(args.candidate)
    import pdfplumber
    for path, record in [(args.reference, reference), (args.candidate, candidate)]:
        with pdfplumber.open(path) as pdf:
            for geometry, page in zip(record['pages'], pdf.pages):
                geometry['lines'] = geometric_lines(page)
    if reference['pageCount'] != candidate['pageCount']:
        raise RuntimeError('Page count differs; inspect both documents before claiming a visual match')
    report = dict(reference=reference, candidate=candidate, dpi=144, comparison=[], contentDifferencesAllowed=args.allow_content_differences)
    version = subprocess.run([tool, '-v'], capture_output=True, text=True, check=True, timeout=10)
    report['rendererVersion'] = (version.stderr or version.stdout).splitlines()[0]
    if args.font_dir:
        report['callerFontSha256'] = {font.name: hashlib.sha256(font.read_bytes()).hexdigest()
                                     for font in sorted(args.font_dir.glob('LiberationSans-*.ttf'))}
    if args.source:
        provenance = json.loads(Path(str(args.reference) + '.provenance.json').read_text(encoding='utf-8-sig'))
        source_hash = hashlib.sha256(args.source.read_bytes()).hexdigest()
        assert source_hash == provenance['sourceSha256'], 'Word reference was exported from different DOCX bytes'
        assert reference['sha256'] == provenance['pdfSha256'], 'Word reference changed after export'
        report['wordExport'] = provenance
    for name, path in [('word', args.reference), ('engine', args.candidate)]:
        subprocess.run([tool, '-png', '-r', '144', str(path.resolve()), str(output / name)], check=True, timeout=120)
        # Poppler pads page numbers once a document reaches ten pages.
        digits = len(str(reference['pageCount']))
        for index in range(1, reference['pageCount'] + 1):
            generated = output / f'{name}-{index:0{digits}d}.png'
            canonical = output / f'{name}-{index}.png'
            if generated != canonical:
                generated.replace(canonical)
    for index, (before, after) in enumerate(zip(reference['pages'], candidate['pages']), start=1):
        word = Image.open(output / f'word-{index}.png').convert('RGB')
        engine = Image.open(output / f'engine-{index}.png').convert('RGB')
        # Pad only at the right/bottom for sub-pixel page-size rounding; never align content.
        size = (max(word.width, engine.width), max(word.height, engine.height))
        a, b = Image.new('RGB', size, 'white'), Image.new('RGB', size, 'white')
        a.paste(word, (0, 0)); b.paste(engine, (0, 0))
        Image.blend(a, b, 0.5).save(output / f'overlay-{index}.png')
        diff = ImageChops.difference(a, b)
        diff.point(lambda value: min(255, value * 4)).save(output / f'difference-{index}.png')
        comparison = Image.new('RGB', (size[0] * 2 + 24, size[1] + 40), '#e5e7eb')
        comparison.paste(a, (0, 40)); comparison.paste(b, (size[0] + 24, 40))
        labels = ImageDraw.Draw(comparison)
        labels.text((16, 12), 'Microsoft Word reference', fill='black')
        labels.text((size[0] + 40, 12), 'NuaForma own engine', fill='black')
        comparison.save(output / f'side-by-side-{index}.png')
        red, green, blue = diff.split()
        maximum = ImageChops.lighter(ImageChops.lighter(red, green), blue)
        changed = sum(maximum.histogram()[25:])
        line_deltas = []
        for left, right in zip(before['lines'], after['lines']):
            line_deltas.append(dict(sameText=left['text'] == right['text'], reference=left['text'], candidate=right['text'],
                xDeltaPoints=round(right['x'] - left['x'], 3),
                baselineTopDeltaPoints=round((after['height'] - right['y']) - (before['height'] - left['y']), 3)))
        report['comparison'].append(dict(page=index, sameNormalizedText=before['text'] == after['text'],
            referenceLineCount=len(before['lines']), candidateLineCount=len(after['lines']),
            changedPixelsAbove24=changed, changedPageFraction=changed / (size[0] * size[1]),
            differenceBoundingBox=diff.getbbox(), lines=line_deltas))
    if args.word_bounds:
        with pdfplumber.open(args.reference) as left, pdfplumber.open(args.candidate) as right:
            for page, a, b in zip(report['comparison'], left.pages, right.pages):
                words_a = a.extract_words(x_tolerance=1, y_tolerance=1)
                words_b = b.extract_words(x_tolerance=1, y_tolerance=1)
                same = [word['text'] for word in words_a] == [word['text'] for word in words_b]
                bounds = dict(sameWords=same, referenceWordCount=len(words_a), candidateWordCount=len(words_b))
                if same and words_a:
                    bounds.update(maxWordStartDeltaPoints=max(abs(y['x0'] - x['x0']) for x, y in zip(words_a, words_b)),
                                  maxWordEndDeltaPoints=max(abs(y['x1'] - x['x1']) for x, y in zip(words_a, words_b)),
                                  maxWordBoxTopDeltaPoints=max(abs(y['top'] - x['top']) for x, y in zip(words_a, words_b)))
                page['wordBounds'] = bounds
    (output / 'comparison.json').write_text(json.dumps(report, indent=2, ensure_ascii=True), encoding='utf-8')
    print(json.dumps(report['comparison'], indent=2, ensure_ascii=True))
    assert args.allow_content_differences or all(page['sameNormalizedText'] for page in report['comparison']), 'PDF text differs; investigate the report'


if __name__ == '__main__':
    main()
