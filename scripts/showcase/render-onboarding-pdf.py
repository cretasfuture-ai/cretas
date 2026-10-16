"""Render all brochure pages for human review; check embedded fonts and text bounds."""
import argparse
import json
from pathlib import Path
import fitz
from PIL import Image, ImageDraw

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--pdf', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    document = fitz.open(args.pdf)
    assert len(document) == 8
    results = []
    sheet = Image.new('RGB', (1120, 4 * 427), '#dfe3e0')
    draw = ImageDraw.Draw(sheet)
    for index, page in enumerate(document):
        assert page.get_text().strip(), f'Page {index + 1} has no text'
        bad = []
        for block in page.get_text('dict')['blocks']:
            for line in block.get('lines', []):
                for span in line['spans']:
                    x0, y0, x1, y1 = span['bbox']
                    if x0 < -0.5 or y0 < -0.5 or x1 > page.rect.width + .5 or y1 > page.rect.height + .5:
                        bad.append(span['text'])
        assert not bad, f'Page {index + 1}: out-of-bounds text {bad}'
        # ReportLab adds an unused standard Helvetica resource by default.
        # Check fonts actually used by page text rather than unused resources.
        used = {span['font'] for block in page.get_text('dict')['blocks']
                for line in block.get('lines', []) for span in line['spans']}
        fonts = [document.extract_font(font[0]) for font in page.get_fonts()
                 if font[3].split('+')[-1] in used]
        assert fonts and all(font[3] for font in fonts), f'Page {index + 1}: used font not embedded'
        pixmap = page.get_pixmap(matrix=fitz.Matrix(1.6, 1.6), alpha=False)
        filename = args.output / f'page-{index + 1:02}.png'
        pixmap.save(filename)
        with Image.open(filename) as image:
            image.thumbnail((540, 390))
            x = 10 + (index % 2) * 560
            y = 25 + (index // 2) * 427
            sheet.paste(image, (x, y))
            draw.text((x, y - 17), f'PAGE {index + 1}', fill='black')
        results.append({'page': index + 1, 'render': str(filename), 'embeddedFonts': len(fonts), 'textBounds': 'PASS'})
    sheet.save(args.output / 'contact-sheet.jpg', quality=93)
    (args.output / 'render-receipt.json').write_text(json.dumps({'status': 'PASS', 'pages': results,
        'visualReview': 'Required separately; automated bounds are not visual acceptance'}, indent=2), encoding='utf-8')
    print(json.dumps({'status': 'PASS', 'pages': len(results), 'contactSheet': str(args.output / 'contact-sheet.jpg')}))

if __name__ == '__main__':
    main()
