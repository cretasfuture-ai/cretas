from pathlib import Path
import shutil
import subprocess
import hashlib
import fitz
import numpy as np

A=Path(__file__).resolve().parent
final=A.parent/'白垩纪Cretas-四件事-5页-V2.pdf'
backup=final.with_name(final.stem+'-备份6.pdf')
assert not backup.exists(), 'Preserve existing backup6.'
shutil.copy2(final,backup)
html=A/'deck-v2.html'
shutil.copy2(html,A/'deck-v2-before-fast-setup.html')
source=html.read_text(encoding='utf-8')
assert source.count('<b>对话建厂</b>')==1
html.write_text(source.replace('<b>对话建厂</b>','<b>配得快</b>'),encoding='utf-8')
subprocess.run(['node',str(A/'render_conversation_setup.cjs')],check=True)
doc=fitz.open(backup)
rendered=fitz.open(A/'conversation-setup-rendered.pdf')
rendered[0].add_redact_annot(rendered[0].rect,fill=None,cross_out=False)
rendered[0].apply_redactions(images=1,graphics=2,text=1)
assert not rendered[0].get_drawings()
clip=fitz.Rect(44,260,130,292)
doc[0].add_redact_annot(clip,fill=None,cross_out=False)
doc[0].apply_redactions(images=0,graphics=0,text=0)
doc[0].show_pdf_page(clip,rendered,0,clip=clip)
candidate=A/'fast-setup-candidate.pdf'
doc.save(candidate,deflate=True)
doc.close()
old,new=fitz.open(backup),fitz.open(candidate)
logs=[]
def report(k,v):
    logs.append(f'{k}={v}')
    print(logs[-1])
assert len(new)==5
report('PDF_PAGES',len(new))
for i in range(1,5):
    a,b=[np.frombuffer(d[i].get_pixmap(matrix=fitz.Matrix(2,2),alpha=False).samples,dtype=np.uint8) for d in (old,new)]
    count=int(np.count_nonzero(a!=b))
    assert count==0
    report(f'PAGE_{i+1}_CHANGED_CHANNELS',count)
a,b=[np.frombuffer(d[0].get_pixmap(matrix=fitz.Matrix(2,2),alpha=False).samples,dtype=np.uint8).reshape(1080,1920,3) for d in (old,new)]
delta=np.any(a!=b,axis=2)
delta[520:584,88:260]=False
assert not delta.any()
report('COVER_OUTSIDE_LABEL_CHANGED_PIXELS',int(delta.sum()))
for label in ['配得快','算得准','管得住','守得牢']:
    assert label in new[0].get_text()
assert '对话建厂' in new[1].get_text()
report('FOUR_PARALLEL_LABELS','PASS')
new[0].get_pixmap(matrix=fitz.Matrix(2,2),alpha=False).save(A/'fast-setup-cover.png')
new[0].get_pixmap(matrix=fitz.Matrix(3,3),clip=fitz.Rect(40,250,490,440),alpha=False).save(A/'fast-setup-cover-detail.png')
report('CANDIDATE_SHA256',hashlib.sha256(candidate.read_bytes()).hexdigest())
report('RESULT','PASS')
(A/'fast-setup-check.txt').write_text('\n'.join(logs)+'\n',encoding='utf-8')
