from pathlib import Path
import hashlib
import json
import shutil
import fitz

A = Path(__file__).resolve().parent
PDF = A.parent / '白垩纪Cretas-四件事-5页-V2.pdf'
BACKUP = PDF.with_name(PDF.stem + '-备份2.pdf')
CANDIDATE = A / 'page5-scene-candidate.pdf'
IMAGE = A / 'security-factory-scene.png'
SOURCE = Path('C:/Users/Steve/.codex/generated_images/01a16ca4-ce60-7eb1-83d3-ee26a1125a16/exec-5176b5ad-1879-43f9-a860-e48966730246.png')
if BACKUP.exists():
    raise SystemExit('Backup2 exists. Inspect rather than overwriting historical evidence.')
shutil.copy2(PDF, BACKUP)
assert hashlib.sha256(PDF.read_bytes()).digest() == hashlib.sha256(BACKUP.read_bytes()).digest()
shutil.copy2(SOURCE, IMAGE)
doc = fitz.open(BACKUP)
page = doc[4]
old_rect = fitz.Rect(354, 320, 606, 404)
old_images = [item[0] for item in page.get_images(full=True) if page.get_image_rects(item[0]) == [old_rect]]
assert len(old_images) == 1
xref = old_images[0]
assert all(xref not in [im[0] for im in doc[i].get_images(full=True)] for i in range(4))
page.delete_image(xref)

# Crop only the empty top/bottom margins at PDF placement time; keep the PNG unmodified.
image_pdf = fitz.open()
ip = image_pdf.new_page(width=2172, height=724)
ip.insert_image(ip.rect, filename=str(IMAGE))
crop = fitz.Rect(0, 220, 2172, 510)
target = fitz.Rect(165, 319.8, 795, 319.8 + 290 * 630 / 2172)
page.show_pdf_page(target, image_pdf, 0, clip=crop, keep_proportion=True)
doc.save(CANDIDATE, deflate=True)
doc.close()
image_pdf.close()

html_path = A / 'deck-v2.html'
shutil.copy2(html_path, A / 'deck-v2-before-page5-scene.html')
html = html_path.read_text(encoding='utf-8')
old = '<img class="security-illustration" src="security-factory-new.png" alt="" aria-hidden="true">'
new = '<div class="security-scene" aria-hidden="true"><img src="security-factory-scene.png" alt=""></div>'
assert html.count(old) == 1
html = html.replace(old, new)
css = '<style>.page5 .security-scene{position:absolute;left:220px;top:426.4px;width:840px;height:112.154696px;overflow:hidden}.page5 .security-scene img{position:absolute;left:0;top:-85.082873px;width:840px;height:280px;max-width:none}</style>'
html = html.replace('</head>', css + '</head>')
html_path.write_text(html, encoding='utf-8')
prompts = json.loads((A / 'page5-scene-prompts.json').read_text(encoding='utf-8'))
with (A / '绘图说明.md').open('a', encoding='utf-8') as f:
    f.write('\n\n## 2026-10-24 第 5 页：连贯的厂内数据安全场景\n\n')
    f.write('最终素材：security-factory-scene.png。初稿改为超宽低矮厂房，将配方和资料锁在厂内档案柜，旁边工人正常生产。原始 PNG 完整保留，仅在 PDF/HTML 放置时裁去上下空白。插画区域宽 840 CSS px，占页面 65.625%，未移动任何卡片、文字或绿色条。PDF 以备份2为底稿，仅替换第5页图片；build_pitch.py 和 render.cjs 未重跑。\n\n')
    f.write('### 初稿完整 image_gen 提示词\n\n' + prompts['initial'] + '\n\n')
    f.write('### 最终图完整 image_gen 编辑提示词\n\n' + prompts['edit'] + '\n\n')
    f.write('编辑输入：exec-b013be56-87d4-4789-8fb3-9246802f4012.png；采用输出：exec-5176b5ad-1879-43f9-a860-e48966730246.png。生成原件均保留在本次 generated_images 目录。\n')
print('BACKUP2_CREATED_AND_HASH_VERIFIED=1')
print('SCENE_WIDTH_PERCENT=65.625')
print('TEXT_AND_CARD_MOVEMENT_PIXELS=0')
print('CANDIDATE=' + str(CANDIDATE))
