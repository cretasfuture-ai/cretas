from pathlib import Path
from collections import Counter
import subprocess
import shutil
import re
import hashlib
import fitz
import numpy as np
from bs4 import BeautifulSoup

A=Path(__file__).resolve().parent
final=A.parent/'白垩纪Cretas-四件事-5页-V2.pdf'
backup=final.with_name(final.stem+'-备份10.pdf')
assert not backup.exists(), 'Preserve existing backup10.'
shutil.copy2(final,backup)
source=A/'deck-v2.html'
shutil.copy2(source,A/'deck-v2-before-four-benefits-restored.html')
soup=BeautifulSoup(source.read_text(encoding='utf-8'),'html.parser')
label=soup.select_one('.page1 .four>div:first-child>b')
assert label.get_text()=='不到一成'
label.string='配得快'
source.write_text(str(soup),encoding='utf-8')
subprocess.run(['node',str(A/'render_final_no_bars.cjs')],check=True)
doc=fitz.open(backup)
rendered=fitz.open(A/'final-no-bars-rendered.pdf')
rendered[0].add_redact_annot(rendered[0].rect,fill=None,cross_out=False)
rendered[0].apply_redactions(images=1,graphics=2,text=1)
assert not rendered[0].get_drawings()
clip=fitz.Rect(44,260,130,292)
doc[0].add_redact_annot(clip,fill=None,cross_out=False)
doc[0].apply_redactions(images=0,graphics=0,text=0)
doc[0].show_pdf_page(clip,rendered,0,clip=clip)
candidate=A/'four-benefits-restored.pdf';doc.save(candidate,deflate=True);doc.close()
old,new=fitz.open(backup),fitz.open(candidate)
logs=[]
def report(k,v):
    logs.append(f'{k}={v}');print(logs[-1])
assert len(new)==5;report('PDF_PAGES',5)
for i in range(1,5):
    a,b=[np.frombuffer(d[i].get_pixmap(matrix=fitz.Matrix(2,2),alpha=False).samples,dtype=np.uint8) for d in (old,new)]
    n=int(np.count_nonzero(a!=b));report(f'PAGE_{i+1}_CHANGED_CHANNELS',n);assert n==0
a,b=[np.frombuffer(d[0].get_pixmap(matrix=fitz.Matrix(2,2),alpha=False).samples,dtype=np.uint8).reshape(1080,1920,3) for d in (old,new)]
delta=np.any(a!=b,axis=2);delta[520:584,88:260]=False
assert not delta.any();report('COVER_OUTSIDE_FIRST_LABEL_CHANGED_PIXELS',int(delta.sum()))
norm=lambda t:re.sub(r'\s','',t)
expected=norm(old[0].get_text()).replace('不到一成','配得快',1)
assert Counter(expected)==Counter(norm(new[0].get_text()))
labels=[b.get_text() for b in soup.select('.page1 .four>div>b')]
assert labels==['配得快','算得准','管得住','守得牢']
assert all(x in new[0].get_text() for x in labels)
report('FOUR_BENEFITS','配得快/算得准/管得住/守得牢')
assert all('定制和维护，不到原来定制的一成价格' in norm(new[i].get_text()) for i in (0,1))
assert '维护免费' in new[1].get_text()
report('LATEST_PRICING_AND_FREE_MAINTENANCE','PASS')
assert all(x in norm(new[1].get_text()) for x in ['对话建厂','修油','滚揉','焯水','卤制','分切','气调包装','记录中心温度'])
report('CONVERSATION_FACTORY_EXAMPLE','PASS')
assert not soup.select('.pain,.restored-pain')
assert not any(x in '\n'.join(p.get_text() for p in new) for x in ['SaaS','软件费全免','按用量'])
report('TOP_EXPLANATION_BARS',0)
assert '误报率 0.18%' in new[3].get_text()
report('VISION_FALSE_ALARM_TEXT','PASS')
assert all(x in new[4].get_text() for x in ['每次查看都留痕','无书面授权不训练','授权默认关闭','阿里云百炼模型','HTTPS 传输加密','也支持本地部署'])
report('SECURITY_PAGE_TEXT','PASS')
assert (330-317.748046875)/.75>=16 and (404.25-390)/.75>=16
report('SECURITY_FEATHER_AND_SPACING','PRESERVED_PIXEL_IDENTICAL')
new[0].get_pixmap(matrix=fitz.Matrix(2,2),alpha=False).save(A/'four-benefits-cover.png')
report('CANDIDATE_SHA256',hashlib.sha256(candidate.read_bytes()).hexdigest())
report('RESULT','PASS')
(A/'four-benefits-audit.txt').write_text('\n'.join(logs)+'\n',encoding='utf-8')
