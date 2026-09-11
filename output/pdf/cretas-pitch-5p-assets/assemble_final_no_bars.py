from pathlib import Path
from collections import Counter
import re
import json
import hashlib
import fitz
import numpy as np

A=Path(__file__).resolve().parent
final=A.parent/'白垩纪Cretas-四件事-5页-V2.pdf'
old=fitz.open(final.with_name(final.stem+'-备份9.pdf'))
rendered=fitz.open(A/'final-no-bars-rendered.pdf')
doc=fitz.open();doc.insert_pdf(old,from_page=0,to_page=0)
doc.insert_pdf(rendered,from_page=1,to_page=3)
doc.insert_pdf(old,from_page=4,to_page=4)
candidate=A/'final-no-bars-candidate.pdf';doc.save(candidate,deflate=True);doc.close()
new=fitz.open(candidate)
logs=[]
def report(k,v):
    logs.append(f'{k}={v}');print(logs[-1])
assert len(new)==5;report('PDF_PAGES',5)
removed=json.loads((A/'final-no-bars-removed.json').read_text(encoding='utf-8'))
chars=lambda t:Counter(re.sub(r'\s','',t))
for i in (1,2,3):
    assert chars(old[i].get_text())-chars(removed[str(i+1)])==chars(new[i].get_text())
    report(f'PAGE_{i+1}_ONLY_BAR_TEXT_REMOVED','PASS')
    new[i].get_pixmap(matrix=fitz.Matrix(2,2),alpha=False).save(A/f'final-no-bars-page-{i+1}.png')
for i in (0,4):
    a,b=[np.frombuffer(d[i].get_pixmap(matrix=fitz.Matrix(2,2),alpha=False).samples,dtype=np.uint8) for d in (old,new)]
    delta=int(np.count_nonzero(a!=b));report(f'PAGE_{i+1}_CHANGED_CHANNELS',delta);assert delta==0
assert '不到原来定制的一成价格' in re.sub(r'\s','',new[1].get_text())
assert '误报率 0.18%' in new[3].get_text()
report('PRICING_AND_ACCURACY_TEXT','UNCHANGED')
report('CANDIDATE_SHA256',hashlib.sha256(candidate.read_bytes()).hexdigest())
report('RESULT','PASS')
(A/'final-no-bars-check.txt').write_text('\n'.join(logs)+'\n',encoding='utf-8')
