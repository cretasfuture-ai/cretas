from pathlib import Path
import fitz
import numpy as np
import hashlib

A = Path(__file__).resolve().parent
final = A.parent / '白垩纪Cretas-四件事-5页-V2.pdf'
backup = final.with_name(final.stem + '-备份3.pdf')
doc = fitz.open(backup)
rendered = fitz.open(A / 'free-maintenance-rendered.pdf')
# A transparent text-only source avoids clipped page-background seams in viewers.
rendered[0].add_redact_annot(rendered[0].rect, fill=None, cross_out=False)
rendered[0].apply_redactions(images=1, graphics=2, text=1)
# Only the first cover description changes. Keep the original cover photo and all other content.
clip = fitz.Rect(137, 262, 480, 291)
doc[0].add_redact_annot(clip, fill=None, cross_out=False)
doc[0].apply_redactions(images=0, graphics=0)
doc[0].show_pdf_page(clip, rendered, 0, clip=clip)
doc.delete_page(1)
doc.insert_pdf(rendered, from_page=1, to_page=1, start_at=1)
candidate = A / 'free-maintenance-candidate.pdf'
doc.save(candidate, deflate=True)
doc.close()
rendered.close()
old, new = fitz.open(backup), fitz.open(candidate)
results = []
def report(key, value):
    line = f'{key}={value}'
    results.append(line)
    print(line)
assert len(new) == 5
report('PDF_PAGES', len(new))
for i in (2, 3, 4):
    a,b = [np.frombuffer(d[i].get_pixmap(matrix=fitz.Matrix(2,2),alpha=False).samples,dtype=np.uint8).reshape(1080,1920,3) for d in (old,new)]
    count = int(np.count_nonzero(np.any(a != b,axis=2)))
    report(f'PAGE_{i+1}_CHANGED_PIXELS',count)
    assert count == 0
a,b = [np.frombuffer(d[0].get_pixmap(matrix=fitz.Matrix(2,2),alpha=False).samples,dtype=np.uint8).reshape(1080,1920,3) for d in (old,new)]
changed = np.any(a != b,axis=2)
changed[524:582,274:960] = False
report('COVER_OUTSIDE_UPDATED_LINE_CHANGED_PIXELS',int(changed.sum()))
assert not changed.any()
assert '软件费、维护费全免，AI 用多少付多少' in new[0].get_text()
assert '软件费、维护费全免，AI 按用量收费' in new[1].get_text()
assert '软件费 + 维护费' in new[1].get_text()
assert '软件和维护都免费' in new[1].get_text()
assert '系统本身不收钱' not in new[1].get_text()
report('FREE_SOFTWARE_AND_MAINTENANCE_TEXT','PASS')
for i in (0,1):
    new[i].get_pixmap(matrix=fitz.Matrix(2,2),alpha=False).save(A / f'free-maintenance-page-{i+1}.png')
report('BACKUP3_EXISTS',int(backup.exists()))
report('CANDIDATE_SHA256',hashlib.sha256(candidate.read_bytes()).hexdigest())
report('RESULT','PASS')
(A / 'free-maintenance-check.txt').write_text('\n'.join(results)+'\n',encoding='utf-8')
