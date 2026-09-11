from pathlib import Path
import fitz

A=Path(__file__).resolve().parent
final=A.parent/'白垩纪Cretas-四件事-5页-V2.pdf'
old=fitz.open(final.with_name(final.stem+'-备份8.pdf'))
rendered=fitz.open(A/'pricing-pain-rendered.pdf')
doc=fitz.open();doc.insert_pdf(old,from_page=0,to_page=0)
doc.insert_pdf(rendered,from_page=1,to_page=3)
doc.insert_pdf(old,from_page=4,to_page=4)
rendered[0].add_redact_annot(rendered[0].rect,fill=None,cross_out=False)
rendered[0].apply_redactions(images=1,graphics=2,text=1)
assert not rendered[0].get_drawings()
clip=fitz.Rect(44,260,480,292)
doc[0].add_redact_annot(clip,fill=None,cross_out=False)
doc[0].apply_redactions(images=0,graphics=0,text=0)
doc[0].show_pdf_page(clip,rendered,0,clip=clip)

# Preserve the artwork bytes. Reposition/crop and feather at the PDF composition layer.
p=doc[4]
scene=[im[0] for im in p.get_images(full=True) if im[2:4]==(2172,724)]
assert len(scene)==1,scene
assert all(scene[0] not in [im[0] for im in doc[i].get_images(full=True)] for i in range(4))
p.delete_image(scene[0])
imagepdf=fitz.open();ip=imagepdf.new_page(width=2172,height=724)
ip.insert_image(ip.rect,filename=str(A/'security-factory-scene.png'))
width=60*2172/290
rect=fitz.Rect((960-width)/2,330,(960+width)/2,390)
p.show_pdf_page(rect,imagepdf,0,clip=fitz.Rect(0,220,2172,510))
dark=(17/255,17/255,17/255)
for side,depth in [('left',18),('right',18),('top',6),('bottom',6)]:
    for i in range(96):
        t=(i+.5)/96
        opacity=1-(3*t*t-2*t*t*t)
        a=depth*i/96;b=depth*(i+1)/96
        if side=='left': r=fitz.Rect(rect.x0+a,rect.y0,rect.x0+b,rect.y1)
        elif side=='right': r=fitz.Rect(rect.x1-b,rect.y0,rect.x1-a,rect.y1)
        elif side=='top': r=fitz.Rect(rect.x0,rect.y0+a,rect.x1,rect.y0+b)
        else:r=fitz.Rect(rect.x0,rect.y1-b,rect.x1,rect.y1-a)
        p.draw_rect(r,color=None,fill=dark,fill_opacity=opacity,overlay=True)
doc.save(A/'pricing-pain-candidate.pdf',deflate=True)
print('CANDIDATE_CREATED=1')
