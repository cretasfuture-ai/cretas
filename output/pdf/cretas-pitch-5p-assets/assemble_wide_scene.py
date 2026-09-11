from pathlib import Path
from PIL import Image
from bs4 import BeautifulSoup
import fitz,json,numpy as np,hashlib
A=Path(__file__).resolve().parent
op=json.loads((A/'wide-scene-operation.json').read_text(encoding='utf-8-sig'))
old=fitz.open(op['backup']);doc=fitz.open(op['backup'])
asset=A/'security-factory-scene-wide-final-alpha.png'
im=Image.open(asset);alpha=np.array(im)[:,:,3]
assert im.mode=='RGBA' and alpha.min()==0
# Visible alpha bounds, with a small transparent safety margin for the fringe.
ys,xs=np.where(alpha>1)
crop=fitz.Rect(max(0,int(xs.min())-1),max(0,int(ys.min())-1),min(im.width,int(xs.max())+2),min(im.height,int(ys.max())+2))
scale=min(630/crop.width,62.1/crop.height)
w,h=crop.width*scale,crop.height*scale
rect=fitz.Rect((960-w)/2,360.85-h/2,(960+w)/2,360.85+h/2)
print('CROP',crop,'DISPLAY_CSS_PX',w/.75,h/.75)
assert w/.75>=760,'Panorama not wide enough at unchanged height'
p=doc[4]
for image in p.get_images(full=True):
 if image[2]>2000 and image[3]>300:p.delete_image(image[0])
p.draw_rect(fitz.Rect(255.30,329.99,704.71,390.01),color=None,fill=(17/255,)*3,overlay=True)
ipdf=fitz.open();ip=ipdf.new_page(width=im.width,height=im.height);ip.insert_image(ip.rect,filename=str(asset));p.show_pdf_page(rect,ipdf,0,clip=crop)
candidate=A/'wide-scene-candidate.pdf';doc.save(candidate,deflate=True)
new=fitz.open(candidate)
assert len(new)==5
for i in range(5):assert new[i].get_text()==old[i].get_text()
for i in range(4):assert new[i].get_pixmap(matrix=fitz.Matrix(2,2)).samples==old[i].get_pixmap(matrix=fitz.Matrix(2,2)).samples
before=np.frombuffer(old[4].get_pixmap(matrix=fitz.Matrix(2,2)).samples,dtype=np.uint8).reshape(1080,1920,3)
after=np.frombuffer(new[4].get_pixmap(matrix=fitz.Matrix(2,2)).samples,dtype=np.uint8).reshape(1080,1920,3)
delta=np.any(before!=after,axis=2);delta[659:785,328:1592]=False;assert not delta.any()
new[4].get_pixmap(matrix=fitz.Matrix(2,2)).save(A/'wide-scene-page-5.png')
# Source uses the same transparent PNG and placement, with no CSS masks.
s=BeautifulSoup((A/'deck-v2.html').read_text(encoding='utf8'),'html.parser');s.select_one('.security-scene img')['src']=asset.name
css=f'''.page5 .security-scene{{left:{rect.x0/.75:.6f}px;top:{rect.y0/.75:.6f}px;width:{w/.75:.6f}px;height:{h/.75:.6f}px}}
.page5 .security-scene img{{width:{im.width*scale/.75:.6f}px;height:{im.height*scale/.75:.6f}px;left:{-crop.x0*scale/.75:.6f}px;top:{-crop.y0*scale/.75:.6f}px}}'''
st=s.new_tag('style');st.string=css;s.head.append(st);(A/'deck-v2.html').write_text(str(s),encoding='utf8')
report={'pages':5,'pages_1_to_4_changed_pixels':0,'all_pages_text_diff':'EMPTY','page5_outside_scene_changed_pixels':0,'display_css_width':w/.75,'display_css_height':h/.75,'page5_top_gap_css_px':(rect.y0-317.748046875)/.75,'page5_bottom_gap_css_px':(404-rect.y1)/.75,'crop':list(crop),'sha256':hashlib.sha256(candidate.read_bytes()).hexdigest()}
assert report['page5_top_gap_css_px']>=16 and report['page5_bottom_gap_css_px']>=16
(A/'wide-scene-check.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf8');print(json.dumps(report,indent=2))
