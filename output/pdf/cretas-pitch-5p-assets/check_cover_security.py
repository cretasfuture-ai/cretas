from pathlib import Path
import difflib
import hashlib
import json
import re
import sys
import fitz
import numpy as np
from PIL import Image

ASSETS = Path(__file__).resolve().parent
PDF = ASSETS.parent / '白垩纪Cretas-四件事-5页-V2.pdf'
BACKUP = PDF.with_name(PDF.stem + '-备份.pdf')
target = PDF if '--final' in sys.argv else ASSETS / 'cover-security-candidate.pdf'
old, new = fitz.open(BACKUP), fitz.open(target)
out = []
def result(key, value):
    out.append(f'{key}={value}')
    print(out[-1])

result('PDF_PAGES', len(new))
assert len(new) == len(old) == 5
assert all(abs(p.rect.width/p.rect.height - 16/9) < 1e-9 for p in new)
result('ALL_PAGES_16_9', 'PASS')
text = '\n'.join(p.get_text() for p in new)
(ASSETS / 'cover-security-fulltext.txt').write_text(text, encoding='utf-8')
matches = re.findall(r'CCP|MES|ERP|DAG|节拍|边缘计算|凭证|按量付费|零误差|追溯', text)
result('FORBIDDEN_TERM_MATCHES', len(matches))
assert not matches
for i in (0, 4):
    a, b = old[i].get_text(), new[i].get_text()
    delta = ''.join(difflib.unified_diff(a.splitlines(True), b.splitlines(True)))
    (ASSETS / f'page-{i+1}-text.diff').write_text(delta, encoding='utf-8')
    result(f'PAGE_{i+1}_TEXT_DIFF_BYTES', len(delta.encode('utf-8')))
    assert a == b
    pix = new[i].get_pixmap(matrix=fitz.Matrix(2, 2), alpha=False)
    pix.save(ASSETS / f'updated-page-{i+1}.png')
for i in (1, 2, 3):
    arrays = []
    for label, doc in [('old', old), ('new', new)]:
        path = ASSETS / f'pixelcheck-{label}-page-{i+1}.png'
        doc[i].get_pixmap(matrix=fitz.Matrix(2, 2), alpha=False).save(path)
        arrays.append(np.asarray(Image.open(path)))
    n = int(np.count_nonzero(np.any(arrays[0] != arrays[1], axis=2)))
    result(f'PAGE_{i+1}_CHANGED_PIXELS', n)
    assert n == 0
for term in ['每次查看都留痕', '无书面授权不训练', '授权默认关闭', '阿里云百炼模型', 'HTTPS 传输加密', '也支持本地部署']:
    assert term in new[4].get_text()
assert '误报率 0.18%' in new[3].get_text()
result('REQUIRED_TEXT', 'PASS')
result('BACKUP_EXISTS', int(BACKUP.exists()))
result('BACKUP_SHA256', hashlib.sha256(BACKUP.read_bytes()).hexdigest())
result('OUTPUT_SHA256', hashlib.sha256(target.read_bytes()).hexdigest())
result('RESULT', 'PASS')
(ASSETS / 'cover-security-check.txt').write_text('\n'.join(out) + '\n', encoding='utf-8')
