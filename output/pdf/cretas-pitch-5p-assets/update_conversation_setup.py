from pathlib import Path
import shutil
import hashlib

A = Path(__file__).resolve().parent
pdf = A.parent / '白垩纪Cretas-四件事-5页-V2.pdf'
backup = pdf.with_name(pdf.stem + '-备份5.pdf')
assert not backup.exists(), 'Preserve existing backup5.'
shutil.copy2(pdf, backup)
assert hashlib.sha256(pdf.read_bytes()).digest() == hashlib.sha256(backup.read_bytes()).digest()
source = A / 'deck-v2.html'
shutil.copy2(source, A / 'deck-v2-before-conversation-setup.html')
html = source.read_text(encoding='utf-8')
old = '<div><b>先用后付</b>软件费、维护费全免，AI 用多少付多少</div>'
new = '<div><b>对话建厂</b>按厂配置，普通 SaaS 收费，维护免费</div>'
assert html.count(old) == 1
html = html.replace(old, new)
start = html.index('<section class="s page2">')
end = html.index('</section>', start) + len('</section>')
page2 = '''<section class="s page2">
<h1>对话建厂：按厂配置，收普通 SaaS 费用</h1>
<div class="pain" style="margin-top:22px"><b>怎么用</b>把产品、工序和分工聊清楚，配出适合自己工厂的系统；维护免费。</div>
<div class="pg2">
<div class="col3">
<div class="b2"><div class="n">聊现场</div><div><h3>说清怎么做</h3><p>做什么产品、经过哪些工序、谁负责，一边聊一边确认。</p></div></div>
<div class="b2"><div class="n">按厂配</div><div><h3>贴合实际生产</h3><p>工艺流程和管理方式按你的工厂来配，做出合用的系统。</p></div></div>
<div class="b2"><div class="n">普通 SaaS</div><div><h3>维护免费</h3><p>收普通 SaaS 的费用，维护不另收费。</p></div></div>
</div>
<div class="shot">
<div class="browser-shot setup-demo">
<div class="browser-top"><i></i><i></i><i></i><span>cretas · 对话建厂</span></div>
<div class="setup-body">
<div class="setup-title">把现场做法，变成系统里的配置 <span>流程示意</span></div>
<div class="setup-user"><b>厂长</b><p>我们做卤猪舌：修油、滚揉、焯水、卤制、分切，最后气调包装。卤制时要测中心温度。</p></div>
<div class="setup-answer"><b>白垩纪 AI</b><p>先按你说的整理成这条流程，再一起确认每道工序怎么报工、谁来负责。</p></div>
<div class="setup-flow"><div>修油</div><i>→</i><div>滚揉</div><i>→</i><div>焯水</div><i>→</i><div class="setup-key">卤制</div><i>→</i><div>分切</div><i>→</i><div>气调包装</div></div>
<div class="setup-detail"><span>卤制报工</span><b>记录中心温度</b><em>把刚才说的要求，放进对应工序</em></div>
<div class="setup-confirm"><span>你确认现场做法，系统按确认结果配置。</span><b>确认配置</b></div>
</div>
</div>
<div class="cap2">对话建厂示意 · 用卤猪舌说明从对话到配置的过程</div>
</div>
</div>
<div class="foot"><span>白垩纪 Cretas · 对话建厂与收费方式</span><span>2</span></div>
</section>'''
html = html[:start] + page2 + html[end:]
css = '''<style>
.page2 .b2:first-child .n{font-size:30px}.page2 .b2:last-child .n{font-size:27px}.page2 .b2 h3{font-size:16px}.page2 .b2 p{font-size:16px;line-height:1.65}
.setup-demo{height:439px}.setup-body{padding:19px 24px;background:#f7faf8;height:411px}.setup-title{font-size:21px;font-weight:700;color:#244437;display:flex;align-items:center;justify-content:space-between;margin-bottom:16px}.setup-title>span{font-size:12px;font-weight:400;color:#7b8e83}
.setup-user,.setup-answer{font-size:16px;line-height:1.6;display:grid;grid-template-columns:75px 1fr;gap:12px;padding:13px 16px;border-radius:9px}.setup-user{background:#e8f3ec;border:1px solid #d2e5d9}.setup-user b{color:#007a55}.setup-answer{margin-top:11px;background:white;border:1px solid #e0e9e3}.setup-answer b{color:#007a55;white-space:nowrap;font-size:15px}.setup-user p,.setup-answer p{color:#31473a}
.setup-flow{display:flex;align-items:center;gap:9px;margin-top:20px}.setup-flow>div{flex:1;text-align:center;border:1px solid #c8ddcf;border-radius:7px;padding:13px 0;font-size:17px;font-weight:700;color:#28563d;background:#fff;white-space:nowrap}.setup-flow i{font-style:normal;font-size:19px;color:#68a184}.setup-flow .setup-key{background:#007a55;color:white;border-color:#007a55}
.setup-detail{margin-top:12px;padding:10px 13px;background:#edf4ef;border-radius:7px;display:flex;align-items:center;gap:13px;font-size:14px;color:#526b5b}.setup-detail b{font-size:15px;color:#007a55}.setup-detail em{font-style:normal;font-size:12px;margin-left:auto;color:#839488}
.setup-confirm{display:flex;align-items:center;justify-content:space-between;margin-top:16px;font-size:14px;color:#64796a}.setup-confirm b{font-size:15px;background:#007a55;color:white;padding:9px 18px;border-radius:6px;font-weight:600}
</style>'''
html = html.replace('</head>', css + '</head>')
source.write_text(html, encoding='utf-8')
print('BACKUP5_CREATED_AND_VERIFIED=1')
print('UPDATED_PAGES=1,2')
print('POSITIONING=CONVERSATION_FACTORY_SETUP_STANDARD_SAAS_FREE_MAINTENANCE')
