from pathlib import Path
import fitz
import numpy as np
import hashlib
import re

A = Path(__file__).resolve().parent
final = A.parent / '白垩纪Cretas-四件事-5页-V2.pdf'
backup = final.with_name(final.stem + '-备份5.pdf')
doc = fitz.open(backup)
rendered = fitz.open(A / 'conversation-setup-rendered.pdf')
# Preserve the existing cover image; insert only the transparent first text row.
rendered[0].add_redact_annot(rendered[0].rect, fill=None, cross_out=False)
rendered[0].apply_redactions(images=1, graphics=2, text=1)
assert not rendered[0].get_drawings()
clip = fitz.Rect(44, 260, 480, 292)
doc[0].add_redact_annot(clip, fill=None, cross_out=False)
doc[0].apply_redactions(images=0, graphics=0, text=0)
doc[0].show_pdf_page(clip, rendered, 0, clip=clip)
doc.delete_page(1)
doc.insert_pdf(rendered, from_page=1, to_page=1, start_at=1)
candidate = A / 'conversation-setup-candidate.pdf'
doc.save(candidate, deflate=True)
doc.close()
old,new = fitz.open(backup),fitz.open(candidate)
logs=[]
def report(k,v):
    logs.append(f'{k}={v}')
    print(logs[-1])
assert len(new)==5
report('PDF_PAGES',len(new))
for i in (2,3,4):
    a,b=[np.frombuffer(d[i].get_pixmap(matrix=fitz.Matrix(2,2),alpha=False).samples,dtype=np.uint8).reshape(1080,1920,3) for d in (old,new)]
    count=int(np.count_nonzero(np.any(a!=b,axis=2)))
    report(f'PAGE_{i+1}_CHANGED_PIXELS',count)
    assert count==0
a,b=[np.frombuffer(d[0].get_pixmap(matrix=fitz.Matrix(2,2),alpha=False).samples,dtype=np.uint8).reshape(1080,1920,3) for d in (old,new)]
delta=np.any(a!=b,axis=2)
delta[520:584,88:960]=False
report('COVER_OUTSIDE_FIRST_ROW_CHANGED_PIXELS',int(delta.sum()))
assert not delta.any()
text='\n'.join(p.get_text() for p in new)
for term in ['软件费全免','软件免费','软件和维护都免费','软件费、维护费全免','定制费','¥0']:
    assert term not in text,term
report('OLD_FREE_SOFTWARE_AND_CUSTOM_FEE_WORDING','ABSENT')
for i in (0,1):
    t=new[i].get_text()
    assert '对话建厂' in t and 'SaaS' in t and '维护免费' in t
    new[i].get_pixmap(matrix=fitz.Matrix(2,2),alpha=False).save(A/f'conversation-setup-page-{i+1}.png')
assert '卤猪舌' in new[1].get_text() and '记录中心温度' in new[1].get_text()
report('CONVERSATION_SETUP_STANDARD_SAAS_FREE_MAINTENANCE','PASS')
(A/'conversation-setup-text.txt').write_text(text,encoding='utf-8')
report('BACKUP5_EXISTS',int(backup.exists()))
report('CANDIDATE_SHA256',hashlib.sha256(candidate.read_bytes()).hexdigest())
report('RESULT','PASS')
(A/'conversation-setup-check.txt').write_text('\n'.join(logs)+'\n',encoding='utf-8')
