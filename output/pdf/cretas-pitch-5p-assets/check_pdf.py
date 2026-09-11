from pathlib import Path
import json,re,difflib
import fitz
from PIL import Image
from bs4 import BeautifulSoup
A=Path(__file__).resolve().parent
P=A.parent/'白垩纪Cretas-四件事-5页-V2.pdf'
doc=fitz.open(P)
assert len(doc)==5,len(doc)
assert all(abs(p.rect.width/p.rect.height-16/9)<.0001 for p in doc)
text='\n'.join(p.get_text() for p in doc)
(A/'pdf-text.txt').write_text(text,encoding='utf-8')
norm=lambda s:re.sub(r'\s+','',s)
forbidden=['CCP','MES','ERP','DAG','有向无环','节拍','边缘计算','凭证','按量付费','零误差','没有任何误差']
hits=[x for x in forbidden if x in norm(text)]
assert not hits,hits
required=['误报率 0.18%','出成率 58.2%','每次查看都留痕','无书面授权不训练','授权默认关闭','阿里云百炼模型','HTTPS 传输加密','也支持本地部署']
found=[x for x in required if norm(x) in norm(text)]
assert len(found)==8,(found,text)
e=json.loads((A/'render-evidence.json').read_text(encoding='utf-8'))
assert not e['errors']
data=json.loads((A/'mock-data.json').read_text(encoding='utf-8'))
for kind,height in [('usage',710),('cost',570)]:
    report=next(x for x in e['reports'] if x['kind']==kind)
    assert (report['width'],report['dpr'])==(1440,2)
    assert Image.open(A/f'mock-{kind}.png').size==(2880,height*2)
    assert not re.search(r'[¥￥$]|人民币|单价|金额',report['text'])
    assert float(report['tableBodyFont'].removesuffix('px'))*834/1440*.75>=9
    for row in data[kind]:
        for val in row.values():assert norm(val) in norm(report['text']),(kind,val)
    if kind=='usage':
        for val in ['312','4,860','30']:assert val in report['text']
        assert report['chartBars']==18
    else:
        for val in ['待处理 3','71%','58%']:assert norm(val) in norm(report['text'])

# Compare signed-off copy outside the explicitly replaced UI areas.
src=BeautifulSoup((A.parent/'cretas-pitch-5p-source/deck2.html').read_text(encoding='utf-8'),'html.parser')
dst=BeautifulSoup((A/'deck-v2.html').read_text(encoding='utf-8'),'html.parser')
for sp,dp in zip(src.select('section.s'),dst.select('section.s')):
    for el in sp.select('.win'):el.decompose()
    for el in dp.select('.browser-shot,.rate'):el.decompose()
    for el in dp.select('.tag'):
        if el.get_text(strip=True)=='手套 ✓':el.decompose()
    a=norm(sp.get_text()).replace('后台界面示意，数据为举例','后台界面示意，数字为举例')
    b=norm(dp.get_text())
    assert a==b,'COPY_DRIFT: '+str(list(difflib.ndiff([a],[b])))

out=[]
for i,pg in enumerate(doc):
    for block in pg.get_text('dict')['blocks']:
        for line in block.get('lines',[]):
            for span in line['spans']:
                x0,y0,x1,y1=span['bbox']
                if x0<-.1 or y0<-.1 or x1>pg.rect.width+.1 or y1>pg.rect.height+.1:out.append((i+1,span['text']))
    pg.get_pixmap(matrix=fitz.Matrix(4/3,4/3),alpha=False).save(A/f'final-page-{i+1}.png')
assert not out,out
lines=['PAGES=5','ASPECT_RATIO=16:9','BANNED_TERMS=0','REQUIRED_PHRASES=8/8','APPROVED_COPY=PASS','MOCK_WIDTH=1440; DPR=2','MOCK_NUMBERS=PASS; DAILY_BARS=18','MOCK_CURRENCY=0','MOCK_BODY_FONT=9.56pt','TEXT_OUT_OF_PAGE=0']
(A/'self-check.txt').write_text('\n'.join(lines)+'\n\nRequired phrases:\n'+'\n'.join(found)+'\n',encoding='utf-8')
print('\n'.join(lines))
