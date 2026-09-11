from pathlib import Path
import hashlib
import json
import shutil
import fitz

ASSETS = Path(__file__).resolve().parent
PDF = ASSETS.parent / '白垩纪Cretas-四件事-5页-V2.pdf'
BACKUP = PDF.with_name(PDF.stem + '-备份.pdf')
CANDIDATE = ASSETS / 'cover-security-candidate.pdf'
GENERATED = Path('C:/Users/Steve/.codex/generated_images/01a16ca4-ce60-7eb1-83d3-ee26a1125a16')

if BACKUP.exists():
    raise SystemExit('Backup already exists; preserve it and inspect before another edit.')
shutil.copy2(PDF, BACKUP)
assert hashlib.sha256(PDF.read_bytes()).digest() == hashlib.sha256(BACKUP.read_bytes()).digest()
shutil.copy2(GENERATED / 'exec-4994582d-3d44-4a05-a96f-11c6331378fd.png', ASSETS / 'cover-workshop-new.png')
shutil.copy2(GENERATED / 'exec-3f7c4315-7ec8-49a9-9f4b-4fa950ea7d59.png', ASSETS / 'security-factory-new.png')

# Keep all original PDF text and pages; only add image/vector content to pages 1 and 5.
doc = fitz.open(BACKUP)
doc[0].insert_image(fitz.Rect(525, 0, 960, 540), filename=str(ASSETS / 'cover-workshop-new.png'), keep_proportion=False)
for i in range(144):
    x = 525 + i * 0.25
    doc[0].draw_rect(fitz.Rect(x, 0, x + 0.25, 540), color=None,
                     fill=(17/255, 17/255, 17/255), fill_opacity=(1 - (i + .5)/144)**2, overlay=True)
doc[4].insert_image(fitz.Rect(354, 320, 606, 404), filename=str(ASSETS / 'security-factory-new.png'))
doc.save(CANDIDATE, deflate=True)
doc.close()

# Narrow edits to the existing HTML preserve the manually corrected page 4.
html_path = ASSETS / 'deck-v2.html'
html = html_path.read_text(encoding='utf-8')
shutil.copy2(html_path, ASSETS / 'deck-v2-before-cover-security.html')
old = "background-image:url('../cretas-pitch-5p-source/p1_0.jpeg');background-position:58% center"
new = "background-image:url('cover-workshop-new.png');background-position:center"
assert html.count(old) == 1
html = html.replace(old, new)
assert html.count('<div class="cta">') == 1
html = html.replace('<div class="cta">', '<img class="security-illustration" src="security-factory-new.png" alt="" aria-hidden="true">\n<div class="cta">')
html = html.replace('</head>', '<style>.page5 .security-illustration{position:absolute;left:472px;top:426.666667px;width:336px;height:112px;object-fit:contain}</style></head>')
html_path.write_text(html, encoding='utf-8')

prompts = json.loads((ASSETS / 'cover-security-prompts.json').read_text(encoding='utf-8'))
with (ASSETS / '绘图说明.md').open('a', encoding='utf-8') as f:
    f.write('\n\n## 2026-10-24 封面与第 5 页定向修改\n\n')
    f.write('仅修改第 1 页右侧照片和第 5 页空白区插画。PDF 以原文件为底稿追加图像，保留原文字对象和第 2–4 页；先保存备份，再生成候选、校验、覆盖。原 build_pitch.py 不重跑；它保留为历史脚本，重跑会重置本次图片修改。更新后的页面源码为 deck-v2.html。\n\n')
    f.write('### 封面照片：cover-workshop-new.png\n\n完整 image_gen 提示词：\n\n' + prompts['cover'] + '\n\n')
    f.write('### 数据安全插画：security-factory-new.png\n\n完整 image_gen 提示词：\n\n' + prompts['security'] + '\n\n')
    f.write('原始生成图片保留于 .codex/generated_images；素材以原始字节复制，未做位图编辑。封面仅通过 PDF 矢量透明渐变衔接左侧黑底。\n')
print('BACKUP_CREATED_AND_HASH_VERIFIED=1')
print('CANDIDATE=' + str(CANDIDATE))
print('SOURCE_EDITS=page_1_image,page_5_image_only')
