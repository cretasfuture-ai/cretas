from pathlib import Path
from collections import Counter
import json
import re
import hashlib
import fitz
import numpy as np

A=Path(__file__).resolve().parent
final=A.parent/'白垩纪Cretas-四件事-5页-V2.pdf'
backup=final.with_name(final.stem+'-备份7.pdf')
old=fitz.open(backup)
rendered=fitz.open(A/'roomier-rendered.pdf')
doc=fitz.open()
doc.insert_pdf(old,from_page=0,to_page=0)
doc.insert_pdf(rendered,from_page=1,to_page=3)
doc.insert_pdf(old,from_page=4,to_page=4)
candidate=A/'roomier-candidate.pdf'
doc.save(candidate,deflate=True)
doc.close()
new=fitz.open(candidate)
logs=[]
def report(k,v):
    logs.append(f'{k}={v}')
    print(logs[-1])
assert len(new)==5
report('PDF_PAGES',len(new))
for i in (0,4):
    a,b=[np.frombuffer(d[i].get_pixmap(matrix=fitz.Matrix(2,2),alpha=False).samples,dtype=np.uint8) for d in (old,new)]
    count=int(np.count_nonzero(a!=b))
    report(f'PAGE_{i+1}_CHANGED_CHANNELS',count)
    assert count==0
removed=json.loads((A/'roomier-removed-text.json').read_text(encoding='utf-8'))
def chars(t):return Counter(re.sub(r'\s','',t))
for i in (1,2,3):
    expected=chars(old[i].get_text())-chars(removed[str(i+1)])
    actual=chars(new[i].get_text())
    assert expected==actual, (i+1,expected-actual,actual-expected)
    report(f'PAGE_{i+1}_OTHER_TEXT','UNCHANGED')
    new[i].get_pixmap(matrix=fitz.Matrix(2,2),alpha=False).save(A/f'roomier-page-{i+1}.png')
assert '误报率 0.18%' in new[3].get_text()
report('REQUIRED_ACCURACY_TEXT','PASS')
report('CANDIDATE_SHA256',hashlib.sha256(candidate.read_bytes()).hexdigest())
report('RESULT','PASS')
(A/'roomier-check.txt').write_text('\n'.join(logs)+'\n',encoding='utf-8')
