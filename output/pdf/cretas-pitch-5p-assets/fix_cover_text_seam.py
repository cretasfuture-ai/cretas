from pathlib import Path
import shutil
import hashlib
import fitz
import numpy as np

A = Path(__file__).resolve().parent
final = A.parent / '白垩纪Cretas-四件事-5页-V2.pdf'
backup = final.with_name(final.stem + '-备份4.pdf')
if backup.exists():
    assert hashlib.sha256(final.read_bytes()).digest() == hashlib.sha256(backup.read_bytes()).digest(), 'Backup4 does not match current input.'
else:
    shutil.copy2(final, backup)
current = fitz.open(backup)
cover_base = fitz.open(final.with_name(final.stem + '-备份3.pdf'))
lettering = fitz.open(A / 'free-maintenance-rendered.pdf')

# Keep only text in the source form. No clipped white/dark background remains
# for PDF viewers to anti-alias as a rectangle around the replaced text.
lettering[0].add_redact_annot(lettering[0].rect, fill=None, cross_out=False)
lettering[0].apply_redactions(images=1, graphics=2, text=1)
assert len(lettering[0].get_drawings()) == 0
assert len(lettering[0].get_image_info()) == 0
clip = fitz.Rect(137, 262, 480, 291)
cover_base[0].add_redact_annot(clip, fill=None, cross_out=False)
cover_base[0].apply_redactions(images=0, graphics=0, text=0)
cover_base[0].show_pdf_page(clip, lettering, 0, clip=clip)
current.delete_page(0)
current.insert_pdf(cover_base, from_page=0, to_page=0, start_at=0)
candidate = A / 'cover-seam-fixed.pdf'
current.save(candidate, deflate=True)
current.close()
old, new = fitz.open(backup), fitz.open(candidate)
logs = []
def report(k, v):
    logs.append(f'{k}={v}')
    print(logs[-1])
report('PDF_PAGES', len(new))
assert len(new) == 5
for i in range(5):
    # Removing the opaque clipped page also removes hidden out-of-clip text;
    # compare visible words in reading order, independently of PDF object order.
    a = [(w[:4], w[4]) for w in old[i].get_text('words', sort=True)]
    b = [(w[:4], w[4]) for w in new[i].get_text('words', sort=True)]
    assert a == b, f'Page {i+1} text changed'
report('ALL_PAGE_TEXT_AND_POSITIONS', 'UNCHANGED')
for i in range(1, 5):
    a,b = [np.frombuffer(d[i].get_pixmap(matrix=fitz.Matrix(2,2),alpha=False).samples,dtype=np.uint8) for d in (old,new)]
    count = int(np.count_nonzero(a != b))
    report(f'PAGE_{i+1}_CHANGED_CHANNELS', count)
    assert count == 0
assert not any(abs(s['rect'].x0-137)<0.01 and abs(s['rect'].y0-262)<0.01 for s in new[0].get_drawings())
report('TEXT_PATCH_BACKGROUND_AND_BORDER', 'REMOVED')
new[0].get_pixmap(matrix=fitz.Matrix(2,2),alpha=False).save(A / 'cover-seam-fixed-page-1.png')
new[0].get_pixmap(matrix=fitz.Matrix(4,4),clip=fitz.Rect(40,250,490,306),alpha=False).save(A / 'cover-seam-fixed-detail.png')
report('SHA256', hashlib.sha256(candidate.read_bytes()).hexdigest())
report('RESULT', 'PASS')
(A / 'cover-seam-check.txt').write_text('\n'.join(logs)+'\n',encoding='utf-8')
