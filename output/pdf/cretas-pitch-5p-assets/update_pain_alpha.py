from pathlib import Path
from bs4 import BeautifulSoup
import shutil,json
A=Path(__file__).resolve().parent
pdf=A.parent/'白垩纪Cretas-四件事-5页-V2.pdf'
n=11
while pdf.with_name(pdf.stem+f'-备份{n}.pdf').exists():n+=1
backup=pdf.with_name(pdf.stem+f'-备份{n}.pdf');shutil.copy2(pdf,backup)
html=A/'deck-v2.html'; snapshot=A/f'deck-v2-before-pain-alpha-{n}.html';assert not snapshot.exists();shutil.copy2(html,snapshot)
s=BeautifulSoup(html.read_text(encoding='utf8'),'html.parser')
pain={
2:'找软件公司定制一套系统，动辄六七位数，还要派工程师驻厂几个月一点点配；改一次流程又要加钱。',
3:'「我们最重要的是算这个产品的毛利、毛利率。」每批原料进价不一样，三四十种辅料，手工表一估就偏，月中才出数。—— 某熟食厂财务',
4:'食品厂的工艺最复杂：一种原料拆成好几个产品，边角料又回到别的产品里，几道工序同时走。普通系统只会一条线记，账一多就乱。'}
assert not s.select('.past-problem,.pain,.restored-pain')
for i,txt in pain.items():
 bar=s.new_tag('div',attrs={'class':'past-problem'});label=s.new_tag('b');label.string='以前';body=s.new_tag('span');body.string=txt;bar.extend([label,body]);s.select_one(f'.page{i} h1').insert_after(bar)
css='''
/* 2026-10-25 Owner: restore pain statements in a new neutral treatment. */
.past-problem{display:flex;align-items:center;gap:14px;margin-top:12px;min-height:55px;padding:9px 15px;background:#f0f2f3;border:1px solid #e5e8ea;border-radius:10px;color:#515b63;font-size:16px;line-height:1.45}
.past-problem>b{flex:none;display:block;background:#dfe4e7;border:1px solid #d2d9dd;color:#4c5861;border-radius:6px;font-size:14px;line-height:26px;padding:0 10px;letter-spacing:1px}
.past-problem>span{display:block}
.page2 .content-fit,.page3 .content-fit,.page4 .content-fit{margin-top:16px;height:494px;overflow:visible}
.page2 .pg2{transform:scale(.96)}.page3 .acc,.page4 .prod{transform:scale(.95)}
.page2 .b2{height:142px;padding:18px 22px}.page2 .b2 h3{top:25px}.page2 .b2 p{margin-top:9px}
.page2 .b2:last-child{height:182px}
.page2 .setup-demo{height:474px}.page2 .setup-body{height:446px;padding:17px 24px;gap:10px}.page2 .setup-user,.page2 .setup-answer{padding:11px 16px}.page2 .setup-flow>div{padding:13px 0}
.page3 .steps{height:500px;gap:12px}.page3 .st{min-height:102px;padding:13px 15px}.page3 .res{padding:16px 19px}.page3 .res .n{font-size:39px}
.page3 .browser-shot{height:392px}.page3 .ai .d{margin-bottom:10px}
.page4 .map{height:500px}.page4 .rt{gap:8px}.page4 .vp{height:280px}.page4 .vl{padding:9px 12px}
'''
style=s.new_tag('style');style.string=css;s.head.append(style)
html.write_text(str(s),encoding='utf8')
(A/'pain-alpha-operation.json').write_text(json.dumps({'backup':str(backup),'snapshot':str(snapshot),'pain':pain},ensure_ascii=False,indent=2),encoding='utf8')
print('BACKUP='+str(backup));print('PAIN_BARS=3')
