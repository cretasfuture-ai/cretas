from pathlib import Path
import difflib
import hashlib
import sys
import fitz
import numpy as np
from PIL import Image

A = Path(__file__).resolve().parent
PDF = A.parent / '白垩纪Cretas-四件事-5页-V2.pdf'
BACKUP = PDF.with_name(PDF.stem + '-备份2.pdf')
target = PDF if '--final' in sys.argv else A / 'page5-scene-candidate.pdf'
old, new = fitz.open(BACKUP), fitz.open(target)
results = []
def report(k, v):
    results.append(f'{k}={v}')
    print(results[-1])
assert len(new) == len(old) == 5
report('PDF_PAGES', len(new))
assert all(abs(p.rect.width/p.rect.height - 16/9) < 1e-9 for p in new)
report('ALL_PAGES_16_9', 'PASS')
for i in range(4):
    arrays = []
    for label, d in [('backup2', old), ('new', new)]:
        path = A / f'scene-{label}-page-{i+1}.png'
        d[i].get_pixmap(matrix=fitz.Matrix(2, 2), alpha=False).save(path)
        arrays.append(np.asarray(Image.open(path)))
    changed = int(np.count_nonzero(np.any(arrays[0] != arrays[1], axis=2)))
    report(f'PAGE_{i+1}_CHANGED_PIXELS', changed)
    assert changed == 0
a, b = old[4].get_text(), new[4].get_text()
delta = ''.join(difflib.unified_diff(a.splitlines(True), b.splitlines(True)))
(A / 'page5-scene-text.diff').write_text(delta, encoding='utf-8')
report('PAGE_5_TEXT_DIFF_BYTES', len(delta.encode('utf-8')))
assert a == b
# Text spans retain not just content but their original locations and font sizes.
def spans(page):
    return [(s['text'], s['bbox'], s['size']) for block in page.get_text('dict')['blocks'] if block['type'] == 0 for line in block['lines'] for s in line['spans']]
assert spans(old[4]) == spans(new[4])
report('PAGE_5_TEXT_POSITIONS_AND_SIZES', 'UNCHANGED')
new[4].get_pixmap(matrix=fitz.Matrix(2, 2), alpha=False).save(A / 'updated-page-5-scene.png')
new[4].get_pixmap(matrix=fitz.Matrix(5, 5), clip=fitz.Rect(165, 319.8, 795, 404), alpha=False).save(A / 'page5-scene-detail.png')
report('BACKUP2_EXISTS', int(BACKUP.exists()))
report('BACKUP2_SHA256', hashlib.sha256(BACKUP.read_bytes()).hexdigest())
report('OUTPUT_SHA256', hashlib.sha256(target.read_bytes()).hexdigest())
report('RESULT', 'PASS')
(A / 'page5-scene-check.txt').write_text('\n'.join(results) + '\n', encoding='utf-8')
