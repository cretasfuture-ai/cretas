from pathlib import Path
import shutil
import hashlib

A = Path(__file__).resolve().parent
pdf = A.parent / '白垩纪Cretas-四件事-5页-V2.pdf'
backup = pdf.with_name(pdf.stem + '-备份3.pdf')
assert not backup.exists(), 'Preserve existing backup3; inspect before proceeding.'
shutil.copy2(pdf, backup)
assert hashlib.sha256(pdf.read_bytes()).digest() == hashlib.sha256(backup.read_bytes()).digest()
source = A / 'deck-v2.html'
shutil.copy2(source, A / 'deck-v2-before-free-maintenance.html')
html = source.read_text(encoding='utf-8')
changes = {
    '软件免费用，按用量收费，用多少都看得见': '软件费、维护费全免，AI 用多少付多少',
    '不用先掏钱：软件免费用，按用量付费': '软件费、维护费全免，AI 按用量收费',
    '<h3>软件使用费</h3>': '<h3>软件费 + 维护费</h3>',
    '系统本身不收钱，不用先做一笔大投入。': '软件和维护都免费，不用先做一笔大投入。',
}
for old, new in changes.items():
    assert html.count(old) == 1, old
    html = html.replace(old, new)
source.write_text(html, encoding='utf-8')
print('BACKUP3_CREATED_AND_VERIFIED=1')
print('UPDATED_TEXT_LOCATIONS=4')
