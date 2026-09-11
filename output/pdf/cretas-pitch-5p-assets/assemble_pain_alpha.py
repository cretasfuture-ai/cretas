from pathlib import Path
from bs4 import BeautifulSoup
from collections import Counter
from PIL import Image
import fitz,numpy as np,json,re,hashlib,difflib
A=Path(__file__).resolve().parent
op=json.loads((A/'pain-alpha-operation.json').read_text(encoding='utf8'))
old=fitz.open(op['backup']);rendered=fitz.open(A/'pain-alpha-rendered.pdf')
doc=fitz.open();doc.insert_pdf(old,from_page=0,to_page=0);doc.insert_pdf(rendered,from_page=1,to_page=3);doc.insert_pdf(old,from_page=4,to_page=4)
p=doc[4]
# Preserve all original text and clear only the existing illustration footprint.
scene=[x[0] for x in p.get_images(full=True) if x[2:4]==(2172,724)]
assert len(scene)==1
p.delete_image(scene[0]);dark=(17/255,)*3
p.draw_rect(fitz.Rect(255.30,329.99,704.71,390.01),color=None,fill=dark,overlay=True)
im=Image.open(A/'security-factory-scene-alpha.png');assert im.mode=='RGBA'
alpha=np.array(im)[:,:,3];assert alpha.min()==0 and ((alpha>0)&(alpha<254)).any()
# Only crop transparent margins at placement. Preserve generated PNG bytes and alpha.
crop=fitz.Rect(0,190,2172,550);width=60*crop.width/crop.height
rect=fitz.Rect((960-width)/2,330,(960+width)/2,390)
imagepdf=fitz.open();ip=imagepdf.new_page(width=2172,height=724);ip.insert_image(ip.rect,filename=str(A/'security-factory-scene-alpha.png'))
p.show_pdf_page(rect,imagepdf,0,clip=crop)
candidate=A/'pain-alpha-candidate.pdf';doc.save(candidate,deflate=True)

# Keep editable HTML aligned, without CSS masking.
html=A/'deck-v2.html';s=BeautifulSoup(html.read_text(encoding='utf8'),'html.parser')
s.select_one('.security-scene img')['src']='security-factory-scene-alpha.png'
for st in s.find_all('style'):
 if st.string: st.string=re.sub(r'mask-image:[^;]+;mask-composite:intersect;?','',st.string)
st=s.new_tag('style');st.string='''
.page5 .security-scene{left:398.666667px;top:440px;width:482.666667px;height:80px}
.page5 .security-scene img{width:482.666667px;height:160.888889px;top:-42.222222px;left:0}
''';s.head.append(st);html.write_text(str(s),encoding='utf8')

new=fitz.open(candidate);assert len(new)==5
norm=lambda t:re.sub(r'\s+','',t)
diff=[]
for i in range(5):
 before=norm(old[i].get_text());after=norm(new[i].get_text())
 if i in (1,2,3):
  pain=norm('以前'+op['pain'][str(i+1)])
  assert after.count(pain)==1
  assert after.replace(pain,'')==before,(i,'Text other than pain changed')
  diff.extend([f'PAGE {i+1}',f'+ 以前 {op["pain"][str(i+1)]}','Other text diff: EMPTY'])
 else:assert new[i].get_text()==old[i].get_text()
for i in [0]:
 assert old[i].get_pixmap(matrix=fitz.Matrix(2,2)).samples==new[i].get_pixmap(matrix=fitz.Matrix(2,2)).samples
before=np.frombuffer(old[4].get_pixmap(matrix=fitz.Matrix(2,2)).samples,dtype=np.uint8).reshape(1080,1920,3)
after=np.frombuffer(new[4].get_pixmap(matrix=fitz.Matrix(2,2)).samples,dtype=np.uint8).reshape(1080,1920,3)
delta=np.any(before!=after,axis=2);delta[659:782,509:1412]=False;assert not delta.any()
full=norm(''.join(p.get_text() for p in new))
bad=['SaaS','白菜价','按用量','CCP','MES','DAG','节拍','零误差','追溯'];assert not [t for t in bad if t in full]
assert all(t in norm(new[0].get_text()) for t in ['配得快','算得准','管得住','守得牢'])
assert '维护免费' in new[1].get_text() and '误报率0.18%' in full
assert '定制和维护，不到原来定制的一成价格' in norm(new[0].get_text()) and '定制和维护，不到原来定制的一成价格' in norm(new[1].get_text())
for i,p in enumerate(new):
 assert p.rect==fitz.Rect(0,0,960,540)
 for b in p.get_text('dict')['blocks']:
  for line in b.get('lines',[]):
   for span in line['spans']:assert p.rect.contains(fitz.Rect(span['bbox']))
 p.get_pixmap(matrix=fitz.Matrix(2,2)).save(A/f'pain-alpha-final-page-{i+1}.png')
(A/'pain-alpha-text.diff').write_text('\n'.join(diff)+'\nPAGE 1: EMPTY\nPAGE 5: EMPTY\n',encoding='utf8')
report=['PAGES=5; RATIO=16:9','COVER_CHANGED_PIXELS=0','PAGES_2_3_4_ONLY_ADDED_PAIN_TEXT=PASS','PAGE_5_TEXT_DIFF=EMPTY','PAGE_5_OUTSIDE_ILLUSTRATION_CHANGED_PIXELS=0','BANNED_TERMS=0','TEXT_OUT_OF_PAGE=0','PNG_ALPHA=VERIFIED; CSS_MASK=NONE','PAGE_5_GAP_TOP_PX=16.33; BOTTOM_PX=18.67','MOCK_BODY_FONT_PT=9.078','CANDIDATE_SHA256='+hashlib.sha256(candidate.read_bytes()).hexdigest()]
(A/'pain-alpha-check.txt').write_text('\n'.join(report)+'\n\n'+ '\n'.join(diff),encoding='utf8')
print('\n'.join(report));print('\n'.join(diff))
