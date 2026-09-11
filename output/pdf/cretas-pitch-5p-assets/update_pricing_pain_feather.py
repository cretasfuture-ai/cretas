from pathlib import Path
import shutil
import json
from bs4 import BeautifulSoup

A=Path(__file__).resolve().parent
final=A.parent/'白垩纪Cretas-四件事-5页-V2.pdf'
backup=final.with_name(final.stem+'-备份8.pdf')
assert not backup.exists(), 'Preserve existing backup8.'
shutil.copy2(final,backup)
source=A/'deck-v2.html'
shutil.copy2(source,A/'deck-v2-before-pricing-pain-feather.html')
soup=BeautifulSoup(source.read_text(encoding='utf-8'),'html.parser')
changes={
 '配得快':'不到一成',
 '按厂配置，普通 SaaS 收费，维护免费':'定制和维护，不到原来定制的一成价格',
 '对话建厂：按厂配置，收普通 SaaS 费用':'定制和维护，不到原来定制的一成价格',
 '普通 SaaS':'不到一成',
 '收普通 SaaS 的费用，维护不另收费。':'AI 听你说完就配好，省下工程师驻厂的人工；定制和维护，不到原来定制的一成价格。',
}
for before,after in changes.items():
    texts=soup.find_all(string=lambda t: t is not None and t.strip()==before)
    assert len(texts)==1,(before,len(texts))
    texts[0].replace_with(after)
pain={
 '2':'找软件公司定制系统，要派工程师驻厂几个月一点点配，价格高、周期长，改一次流程又要加钱。',
 '3':'「我们最重要的是算这个产品的毛利、毛利率。」每批原料进价不一样，三四十种辅料，手工表一估就偏，月中才出数。—— 某熟食厂财务',
 '4':'食品厂的工艺最复杂：一种原料拆成好几个产品，边角料又回到别的产品里，几道工序同时走。普通系统只会一条线记，账一多就乱。',
}
for n,txt in pain.items():
    section=soup.select_one('.page'+n)
    strip=soup.new_tag('div',attrs={'class':'pain restored-pain'})
    label=soup.new_tag('b');label.string='以前'
    strip.append(label);strip.append(txt)
    section.h1.insert_after(strip)
    group=section.select_one({'2':'.pg2','3':'.acc','4':'.prod'}[n])
    wrapper=soup.new_tag('div',attrs={'class':'content-fit'})
    group.wrap(wrapper)
css='''
.page2 .restored-pain,.page3 .restored-pain,.page4 .restored-pain{margin-top:16px!important;font-size:16px;line-height:1.55;padding:11px 18px;background:#f6f3ef;border-left:3px solid #c9a27a;border-radius:7px;color:#444}
.restored-pain b{color:#8a5a2b;font-weight:700;margin-right:8px}
.page2 .content-fit,.page3 .content-fit,.page4 .content-fit{position:relative;margin-top:20px;width:1164px;overflow:hidden}
.page2 .content-fit{height:478px}.page3 .content-fit{height:480px}.page4 .content-fit{height:462px}
.page2 .pg2,.page3 .acc,.page4 .prod{margin-top:0!important;transform-origin:top center}
.page2 .pg2,.page3 .acc{transform:scale(.84)}.page4 .prod{transform:scale(.86)}
.page2 .b2:last-child{height:208px}.page2 .b2:last-child .n{font-size:30px}.page2 .b2:last-child p{font-size:17px;line-height:1.7}
.page5 .security-scene{left:340.413793px;top:440px;width:599.172414px;height:80px;mask-image:linear-gradient(to right,transparent,#000 4%,#000 96%,transparent),linear-gradient(to bottom,transparent,#000 10%,#000 90%,transparent);mask-composite:intersect}
.page5 .security-scene img{width:599.172414px;height:auto;top:-60.689655px}
'''
style=soup.new_tag('style');style.string=css;soup.head.append(style)
source.write_text(str(soup),encoding='utf-8')
(A/'pricing-pain-changes.json').write_text(json.dumps({'replacements':changes,'pain':pain},ensure_ascii=False,indent=2),encoding='utf-8')
print('BACKUP8_CREATED=1')
print('PRICING=LESS_THAN_ONE_TENTH_INCLUDING_MAINTENANCE')
print('PAIN_BARS_ADDED=3')
