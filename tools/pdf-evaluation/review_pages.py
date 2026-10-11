"""Create ignored, pixel-deduplicated full-page QA sheets without cropping."""
import argparse
import hashlib
import json
import pathlib
from PIL import Image, ImageDraw


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run', type=pathlib.Path)
    args = parser.parse_args()
    pages = sorted(args.run.glob('libreoffice/*/page-*.png'))
    unique = {}
    for page in pages:
        with Image.open(page) as image:
            key = (image.size, hashlib.sha256(image.tobytes()).hexdigest())
        unique.setdefault(key, []).append(str(page.relative_to(args.run)))
    groups = list(unique.values())
    cell_width = max(size[0] for size, _ in unique) + 10
    cell_height = max(size[1] for size, _ in unique) + 45
    output = args.run / 'review'
    output.mkdir(exist_ok=False)
    for offset in range(0, len(groups), 4):
        sheet = Image.new('RGB', (cell_width * 2, cell_height * 2), 'white')
        draw = ImageDraw.Draw(sheet)
        for index, group in enumerate(groups[offset:offset + 4]):
            x, y = (index % 2) * cell_width, (index // 2) * cell_height
            draw.text((x + 5, y + 5), group[0], fill='black')
            with Image.open(args.run / group[0]) as page:
                sheet.paste(page.convert('RGB'), (x + 5, y + 35))
        sheet.save(output / f'sheet-{offset // 4 + 1}.png')
    (output / 'coverage.json').write_text(json.dumps(groups, indent=2))
    print(json.dumps(dict(pages=len(pages), unique=len(groups), sheets=(len(groups) + 3) // 4)))


if __name__ == '__main__':
    main()
