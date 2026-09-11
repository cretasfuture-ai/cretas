from pathlib import Path
import json
import shutil
from bs4 import BeautifulSoup, Comment

A=Path(__file__).resolve().parent
pdf=A.parent/'白垩纪Cretas-四件事-5页-V2.pdf'
backup=pdf.with_name(pdf.stem+'-备份9.pdf')
assert not backup.exists(), 'Preserve existing backup9.'
shutil.copy2(pdf,backup)
source=A/'deck-v2.html'
shutil.copy2(source,A/'deck-v2-before-final-no-bars.html')
soup=BeautifulSoup(source.read_text(encoding='utf-8'),'html.parser')
removed={}
for n in (2,3,4):
    bar=soup.select_one(f'.page{n} .pain')
    assert bar is not None
    removed[str(n)]=bar.get_text()
    bar.decompose()
assert not soup.select('.pain,.restored-pain')
soup.head.append(Comment('2026-10-25 最新确认：第 2–4 页删除顶部痛点说明条。保留不到一成的收费口径和第 5 页渐隐插画。不要通过旧生成脚本恢复说明条。'))
style=soup.new_tag('style')
style.string='''
.page2 .content-fit,.page3 .content-fit,.page4 .content-fit{margin-top:24px}
.page2 .content-fit,.page3 .content-fit{height:550px}.page4 .content-fit{height:540px}
.page2 .pg2{transform:scale(.96)}.page3 .acc,.page4 .prod{transform:none}
'''
soup.head.append(style)
source.write_text(str(soup),encoding='utf-8')
(A/'final-no-bars-removed.json').write_text(json.dumps(removed,ensure_ascii=False,indent=2),encoding='utf-8')
print('BACKUP9_CREATED=1')
print('REMOVED_TOP_BARS=3')
