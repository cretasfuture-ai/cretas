from pathlib import Path
from collections import Counter
import difflib
import hashlib
import json
import re
import fitz
import numpy as np

A=Path(__file__).resolve().parent
final=A.parent/'白垩纪Cretas-四件事-5页-V2.pdf'
old=fitz.open(final.with_name(final.stem+'-备份8.pdf'))
candidate=A/'pricing-pain-candidate.pdf';new=fitz.open(candidate)
changes=json.loads((A/'pricing-pain-changes.json').read_text(encoding='utf-8'))
logs=[]
def report(k,v):
    logs.append(f'{k}={v}');print(logs[-1])
def norm(t):return re.sub(r'\s','',t)
assert len(new)==5
report('PDF_PAGES',len(new))
text='\n'.join(p.get_text() for p in new)
(A/'pricing-pain-fulltext.txt').write_text(text,encoding='utf-8')
for term in ['SaaS','白菜价','按用量','CCP','MES','DAG','节拍','零误差','追溯']:
    count=text.count(term);report(f'FORBIDDEN_{term}',count);assert count==0
report('一成_COUNT',text.count('一成'));assert text.count('一成')>=2
assert '不到原来定制的一成价格' in norm(new[0].get_text())
assert '不到原来定制的一成价格' in norm(new[1].get_text())
diff=[]
for i in range(5):
    before=old[i].get_text(sort=True)
    after=new[i].get_text(sort=True)
    diff.extend(difflib.unified_diff(before.splitlines(True),after.splitlines(True),fromfile=f'backup8/page{i+1}',tofile=f'updated/page{i+1}'))
    expected=norm(old[i].get_text())
    if i in (0,1):
        # Long replacements first, so "普通 SaaS" does not consume the body substring.
        for a,b in sorted(changes['replacements'].items(),key=lambda kv:-len(kv[0])):
            expected=expected.replace(norm(a),norm(b))
    if i in (1,2,3):expected+='以前'+norm(changes['pain'][str(i+1)])
    assert Counter(expected)==Counter(norm(new[i].get_text())),(i+1,Counter(expected)-Counter(norm(new[i].get_text())),Counter(norm(new[i].get_text()))-Counter(expected))
    report(f'PAGE_{i+1}_AUTHORIZED_TEXT_ONLY','PASS')
    new[i].get_pixmap(matrix=fitz.Matrix(2,2),alpha=False).save(A/f'pricing-pain-page-{i+1}.png')
(A/'pricing-pain-text.diff').write_text(''.join(diff),encoding='utf-8')
assert old[4].get_text()==new[4].get_text()
report('PAGE_5_TEXT_DIFF_BYTES',0)
assert '误报率 0.18%' in new[3].get_text()
report('PAGE_4_ACCURACY_TEXT','PASS')
# Pixel checks outside the two narrowly edited areas on pages 1 and 5.
for i,area in [(0,(88,520,960,584)),(4,(328,638,1592,810))]:
    a,b=[np.frombuffer(d[i].get_pixmap(matrix=fitz.Matrix(2,2),alpha=False).samples,dtype=np.uint8).reshape(1080,1920,3) for d in (old,new)]
    delta=np.any(a!=b,axis=2)
    x0,y0,x1,y1=area;delta[y0:y1,x0:x1]=False
    count=int(delta.sum());report(f'PAGE_{i+1}_OUTSIDE_EDIT_CHANGED_PIXELS',count);assert count==0
top_text=max(b[3] for b in new[4].get_text('blocks') if 280<b[1]<320)
top_gap=(330-top_text)/.75
bottom_gap=(404.25-390)/.75
report('PAGE_5_TOP_GAP_PX',round(top_gap,2));report('PAGE_5_BOTTOM_GAP_PX',round(bottom_gap,2))
assert top_gap>=16 and bottom_gap>=16
report('BACKUP8_EXISTS',1)
report('CANDIDATE_SHA256',hashlib.sha256(candidate.read_bytes()).hexdigest())
report('RESULT','PASS')
(A/'pricing-pain-check.txt').write_text('\n'.join(logs)+'\n',encoding='utf-8')
