from pathlib import Path
import json
import re
import shutil
from bs4 import BeautifulSoup

A=Path(__file__).resolve().parent
pdf=A.parent/'白垩纪Cretas-四件事-5页-V2.pdf'
backup=pdf.with_name(pdf.stem+'-备份7.pdf')
assert not backup.exists(), 'Preserve existing backup7.'
shutil.copy2(pdf,backup)
source=A/'deck-v2.html'
shutil.copy2(source,A/'deck-v2-before-roomier-pages.html')
shutil.copy2(A/'mock-cost.html',A/'mock-cost-before-roomier.html')
shutil.copy2(A/'mock-cost.png',A/'mock-cost-before-roomier.png')
soup=BeautifulSoup(source.read_text(encoding='utf-8'),'html.parser')
removed={}
for i in (2,3,4):
    pain=soup.select_one(f'.page{i} .pain')
    assert pain is not None
    removed[str(i)]=pain.get_text()
    pain.decompose()
assert not soup.select('.pain')

# Spread the vertical branches without stretching the illustrations.
graph=soup.select_one('.page4 .process-svg')
graph['height']='440'
graph['viewbox']='0 0 690 440'
offsets=[35,35,8,35,62,8,35,21,48]
arts=graph.select('svg.art')
assert len(arts)==len(offsets)
for art,dy in zip(arts,offsets):
    art['y']=str(float(art['y'])+dy)
for path in graph.select(':scope > g > path, :scope > path'):
    tokens=re.findall(r'[MLC]|-?\d+(?:\.\d+)?',path['d'])
    nums=0
    output=[]
    for t in tokens:
        if t in ('M','L','C'):
            output.append(t)
            nums=0
        else:
            v=float(t)
            if nums%2==1:
                v=v+(v-124)*27/109+8
            output.append(f'{v:.3f}'.rstrip('0').rstrip('.'))
            nums+=1
    path['d']=' '.join(output)
for label in soup.select('.page4 .process-label'):
    style=label['style']
    top=int(re.search(r'top:(\d+)px',style).group(1))
    dy={110:8,219:35,328:62,164:21,273:48}[top]
    label['style']=re.sub(r'top:\d+px',f'top:{top+dy}px',style)
css='''
.page2 .pg2,.page3 .acc,.page4 .prod{margin-top:24px}
.page2 .b2{height:164px;padding:22px}.page2 .b2 h3{top:29px}.page2 .b2 p{font-size:17px;line-height:1.7;margin-top:12px}
.page2 .setup-demo{height:520px}.page2 .setup-body{height:492px;padding:23px 24px;display:flex;flex-direction:column;justify-content:space-between;gap:12px}.page2 .setup-title{font-size:23px;margin:0}.page2 .setup-user,.page2 .setup-answer{font-size:17px;padding:15px 16px;margin:0;line-height:1.65}.page2 .setup-flow{margin:0;gap:9px}.page2 .setup-flow>div{font-size:18px;padding:16px 0}.page2 .setup-detail{margin:0;padding:12px 13px}.page2 .setup-confirm{margin:0}
.page3 .steps{height:535px;gap:14px}.page3 .st{min-height:112px;padding:18px 15px}.page3 .st span{font-size:16px;line-height:1.6}.page3 .res{flex:1;padding:20px}.page3 .res .n{font-size:41px}.page3 .ai .d{font-size:16px;line-height:1.55;margin-bottom:12px}
.page4 .map{height:500px}.page4 .process-svg{width:100%}.page4 .vp{height:300px}.page4 .vision-overlay{height:242px}.page4 .rt{gap:10px}.page4 .rework-label{top:353px}.page4 .vl{padding:11px 12px}.page4 .vn{margin-top:1px}
'''
style=soup.new_tag('style')
style.string=css
soup.head.append(style)
source.write_text(str(soup),encoding='utf-8')
(A/'roomier-removed-text.json').write_text(json.dumps(removed,ensure_ascii=False,indent=2),encoding='utf-8')
mock=A/'mock-cost.html'
text=mock.read_text(encoding='utf-8')
text=text.replace('</head>','<style>.cost .el-table td.el-table__cell{padding:18px 0}.cost .el-table th.el-table__cell{padding:12px 0}.cost .el-table .cell{line-height:32px}.cost .detail{height:124px;margin-top:16px}.cost .detail .comparison{gap:15px}.cost .detail .track,.cost .detail .fill{height:23px}.cost main{padding-top:18px}</style></head>')
mock.write_text(text,encoding='utf-8')
print('BACKUP7_CREATED=1')
print('REMOVED_EXPLANATION_BARS=3')
print('UPDATED_PAGES=2,3,4')
