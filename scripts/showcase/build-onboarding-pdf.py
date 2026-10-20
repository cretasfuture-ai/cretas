"""Customer brochure. Real local screenshots; no internal questionnaire or credentials."""
from pathlib import Path
import argparse,hashlib,json,shutil
from reportlab.pdfgen import canvas
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.lib.colors import HexColor,Color,white
from reportlab.platypus import Paragraph
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.utils import ImageReader
from reportlab.lib.pagesizes import A4,landscape
from PIL import Image

ROOT=Path(__file__).resolve().parents[2]
W,H=landscape(A4);M=38;CW=W-2*M
INK='#142c23';GREEN='#007a55';MUTED='#526159';LINE='#dce3dd';SOFT='#eef4ef';BG='#fdfcfa'
def main():
 ap=argparse.ArgumentParser();ap.add_argument('--captures',type=Path,required=True);ap.add_argument('--output',type=Path,default=ROOT/'output/pdf/cretas-factory-onboarding.pdf');args=ap.parse_args()
 for f in ['conversation','indicators','trace-upstream','trace-output','trace-shipment','cost-detail']:assert (args.captures/(f+'.png')).is_file(),f
 pdfmetrics.registerFont(TTFont('Cretas','C:/Windows/Fonts/msyh.ttc',subfontIndex=0));pdfmetrics.registerFont(TTFont('CretasBold','C:/Windows/Fonts/msyhbd.ttc',subfontIndex=0));pdfmetrics.registerFontFamily('Cretas',normal='Cretas',bold='CretasBold')
 args.output.parent.mkdir(parents=True,exist_ok=True);c=canvas.Canvas(str(args.output),pagesize=(W,H),pageCompression=1)
 c.setTitle('对话建厂｜能力与实施说明');c.setAuthor('白垩纪 Cretas');c.setSubject('代表性产品、工厂共用基础、实施配合与真实演示界面')
 def fill(color):c.setFillColor(HexColor(color))
 def text(t,x,y,size=12,color=INK,bold=False):
  fill(color);c.setFont('CretasBold' if bold else 'Cretas',size);c.drawString(x,y,t)
 def para(t,x,y,width,size=12,color=MUTED,bold=False,leading=None):
  style=ParagraphStyle('p',fontName='CretasBold' if bold else 'Cretas',fontSize=size,leading=leading or size*1.75,textColor=HexColor(color),wordWrap='CJK',spaceAfter=0)
  p=Paragraph(t,style);_,h=p.wrap(width,1000);p.drawOn(c,x,y-h);return h
 def rule(y,x=M,width=CW):c.setStrokeColor(HexColor(LINE));c.setLineWidth(.6);c.line(x,y,x+width,y)
 def brand(dark=False):
  fg='#ffffff' if dark else INK
  for x,y,green in [(0,7,False),(7,7,False),(0,0,False),(7,0,True)]:
   fill('#00b377' if green else fg);c.roundRect(M+x,H-35+y,5.5,5.5,1.2,fill=1,stroke=0)
  text('白垩纪',M+23,H-29,13,fg,True);text('CRETAS',M+77,H-29,9,'#9bc6af' if dark else GREEN)
 def footer(n,dark=False):
  color='#bbd0c3' if dark else MUTED
  text('对话建厂 · 客户沟通版  |  2026.10',M,21,8.5,color)
  text('cretaceousfuture.com',W-175,21,8.5,color);text(f'{n:02d} / 08',W-66,21,8.5,color)
 def page(n,title,sub=None):
  fill(BG);c.rect(0,0,W,H,fill=1,stroke=0);brand();text(title,M,H-89,29,INK,True)
  if sub:para(sub,M,H-105,CW,10.7,MUTED)
  footer(n)
 def screenshot(key,x,y,width,height,crop=None):
  p=args.captures/(key+'.png');im=Image.open(p);iw,ih=im.size
  if crop:
   l,t,r,b=crop;cropw=(r-l)*iw;croph=(b-t)*ih;scale=min(width/cropw,height/croph)
   dw,dh=cropw*scale,croph*scale;dx=x+(width-dw)/2;dy=y+(height-dh)/2
   c.saveState();clip=c.beginPath();clip.rect(dx,dy,dw,dh);c.clipPath(clip,stroke=0,fill=0)
   c.drawImage(ImageReader(im),dx-l*iw*scale,dy-(ih-b*ih)*scale,width=iw*scale,height=ih*scale,mask='auto');c.restoreState()
  else:
   scale=min(width/iw,height/ih);dw,dh=iw*scale,ih*scale;dx=x+(width-dw)/2;dy=y+(height-dh)/2
   c.drawImage(ImageReader(im),dx,dy,width=dw,height=dh,mask='auto')
  c.setStrokeColor(HexColor(LINE));c.setLineWidth(.6);c.rect(dx,dy,dw,dh,fill=0,stroke=1)
  return dx,dy,dw,dh

 # 1 - promise and method, without quantified commercial claims.
 fill(INK);c.rect(0,0,W,H,fill=1,stroke=0);brand(True)
 text('对话建厂',M,480,14,'#72e3b2',True)
 for i,line in enumerate(['从代表性产品开始，','建立工厂','数字底座。']):text(line,M,415-i*60,39,'#ffffff',True)
 para('把实际业务讲清楚，把配置方案看明白。<br/>先跑通首批完整业务，再扩展更多产品和场景。',M,207,400,13,'#d5e4db')
 screenshot('conversation',480,187,322,300)
 text('真实本地界面 · 演示专用工厂',486,171,9,'#bbd0c3')
 c.setStrokeColor(HexColor('#466554'));c.line(M,120,W-M,120)
 for i,(a,b) in enumerate([('业务沟通','确认工厂事实'),('方案复核','明确配置范围'),('首批验收','核对实际数据'),('持续扩展','补充业务差异')]):
  x=M+i*(CW/4);text(a,x,88,15,'#ffffff',True);text(b,x,65,10,'#bbd0c3')
 footer(1,True);c.showPage()

 # 2 - abstract discovery map. Counts are scale, not a public questionnaire.
 page(2,'七个业务领域，明确建设范围。','先看业务方向和交付结果；具体确认内容随工厂、工序和产品数量展开。')
 widths=[137,137,230,CW-504];xs=[M]
 for width in widths:xs.append(xs[-1]+width)
 fill(INK);c.rect(M,421,CW,29,fill=1,stroke=0)
 for i,t in enumerate(['业务领域','大致确认规模','主要了解方向','形成结果']):text(t,xs[i]+10,431,10,'#ffffff',True)
 rows=[
 ('业务定位','约2项基础确认','主要产品、业务类型与行业场景','首批范围与适用场景'),
 ('产品与规格','约2项，按规格补充','代表性产品、包装与计量方式','首批产品与规格档案'),
 ('工艺与路线','按工序和产品展开','投入产出、工艺要求和特殊路线','可复核的工序与产品流程'),
 ('物料与仓储','约3项，按资料补充','来源归属、物料分类、存放区域','物料及仓储管理基础'),
 ('质量与控制标准','约2项，按控制点展开','关键质量环节与判定依据','需要执行和记录的质量要求'),
 ('经营关注与提醒','约3项，按关注项展开','关注指标、口径及异常条件','管理看板与提醒需求'),
 ('功能与人员使用','约2项，按岗位补充','首批功能、人员职责及账号','功能范围与人员使用安排')]
 y=421
 for rown,row in enumerate(rows):
  y-=43
  if rown%2==0:fill(SOFT);c.rect(M,y,CW,43,fill=1,stroke=0)
  for i,t in enumerate(row):para(t,xs[i]+10,y+30,widths[i]-20,10,INK if i==0 else MUTED,i==0,15)
  rule(y)
 para('工艺部分通常涉及每道工序约8项、每个产品约7项信息确认。已有资料或一次完整回答可以覆盖多项，信息项数量不等于对话轮数。',M,94,CW,10,MUTED)
 c.showPage()

 # 3 - reusable foundation and controlled expansion.
 page(3,'先把代表性产品做扎实。','选择能够覆盖主要差异的产品，并确认它们从原料到出货的完整链路。')
 col=(CW-44)/3
 for i,(title,body,result) in enumerate([
 ('全厂共用基础','物料分类、计量单位、仓储区域、工序目录和岗位安排，为后续产品提供可复用的基础。','可复用的基础配置'),
 ('首批完整业务','产品规格、配方、路线与必要规则经过确认；实际操作后核对追溯、成本和经营数据。','经过验收的业务范围'),
 ('后续扩展覆盖','增加其他产品、特殊路线和更细的管理要求；新增差异继续确认和验收。','按业务差异逐步扩展')]):
  x=M+i*(col+22);c.setStrokeColor(HexColor(GREEN));c.setLineWidth(2);c.line(x,435,x+col,435)
  text(title,x,406,20,INK,True);para(body,x,382,col,12);text(result,x,284,11,GREEN,True)
 fill(SOFT);c.roundRect(M,121,CW,126,10,fill=1,stroke=0)
 text('一个共用基础，支持不同产品的完整流程',M+20,222,14,INK,True)
 text('共用资料 / 共用工序',M+25,174,13,INK,True)
 text('→',M+211,174,18,GREEN);text('散装产品：按重量管理',M+250,184,13,INK)
 text('盒装产品：按净含量与包装换算管理',M+250,154,13,INK)
 para('流程示意：具体工序、路线及计量关系以本厂确认为准。首批产品不能替代全厂其他产品的事实核对。',M,99,CW,10)
 c.showPage()

 # 4 - customer cooperation.
 page(4,'工厂需要怎样配合？','指定一位统筹人，按阶段安排生产、仓库、质量和财务等人员参与；岗位可由同一人兼任。')
 rows=[
 ('确定首批范围','选择代表性产品与完整业务场景。','首批目标、负责人和验收范围。'),
 ('提供已有资料','提供产品、工艺、配方、仓库、质检表和单据样例。','可复用资料与待补充清单。'),
 ('参与业务确认','核对关键事实，补充工艺、质量及成本规则。','可复核的工厂配置方案。'),
 ('审核配置方案','确认产品流程、功能范围与岗位使用。','本次启用内容及尚未完成项。'),
 ('配合首批操作','按正常岗位完成收料、生产、检验、入库和出货。','实际业务链路及问题处理记录。'),
 ('验收并扩展','核对来源、投入产出、成本、出货及同期间指标。','已验收范围和下一批扩展计划。')]
 text('阶段',M+25,433,10,GREEN,True);text('工厂主要配合',M+200,433,10,GREEN,True);text('共同产出',M+510,433,10,GREEN,True)
 y=416
 for i,(title,body,result) in enumerate(rows):
  rule(y);text(f'{i+1:02}',M,y-27,10,GREEN);text(title,M+25,y-28,13,INK,True)
  para(body,M+200,y-15,277,11);para(result,M+510,y-15,CW-510,11,INK);y-=53
 para('开始前无需把所有资料重新整理一遍。首批使用必需的配方、单位换算、质量标准和岗位权限，需要在对应业务启用前确认齐备。',M,78,CW,10)
 c.showPage()

 # 5 - actual platform guidance.
 page(5,'真实界面：一次确认一件业务事实。','平台管理员组织配置，工厂负责人参与确认。以下展示起始引导，不表示整厂方案已经生成或启用。')
 screenshot('conversation',M,85,535,355,crop=(0,.16,1,1))
 x=602
 for y,title,body in [(421,'解释为什么要确认','当前问题会说明它影响哪些产品、工序或物料，方便业务人员判断。'),(303,'建议可以改，答案由人确认','可编辑例子帮助表达实际情况，不把参考建议直接当作工厂事实。'),(185,'待补内容继续保留','进度和未完成项可见；不确定的内容需要后续补充。')]:
  text(title,x,y,13,INK,True);para(body,x,y-18,W-M-x,11)
 text('实拍来源：本地演示专用工厂。没有执行生产业务或发布配置。',M,57,9,MUTED)
 c.showPage()

 # 6 - actual business data, no frozen cost promise.
 page(6,'真实界面：经营指标有业务来源。','工厂人员在日常模块操作，经营指标读取实际记录；建厂配置本身不等于已经产生经营数据。')
 screenshot('indicators',M,107,CW,337,crop=(0,.075,1,.78))
 for i,(title,body) in enumerate([('同一统计期间','用相同日期范围核对指标与完工批次。'),('原单位保留','包装与重量的分析依据需要明确确认。'),('数据状态清楚','区分可用、缺数和计算失败。')]):
  x=M+i*(CW/3);text(title,x,84,12,GREEN,True);para(body,x,65,CW/3-24,9.5)
 text('实拍来源：本地模拟业务。数值是演示快照，不代表客户成效或收益承诺。',M,39,8.5,MUTED)
 c.showPage()

 # 7 - complementary, original-image crops; no reconstructed interface.
 page(7,'真实界面：一批货的来处与去处。','同一盒装演示批次，分别核对原料来源、生产事实和已发生的出货记录。截图为原界面局部。')
 text('原料来源与上游批次',M,435,12,GREEN,True)
 screenshot('trace-upstream',M,192,310,228)
 text('合格与不良产出',400,435,12,GREEN,True)
 screenshot('trace-output',400,269,404,151)
 para('演示批次：合格产出20盒、不良产出2盒。实际出货10盒。页面同时保留待检验状态与未登记检验记录的提示。',400,246,404,11)
 text('实际出货记录',M,168,12,GREEN,True)
 screenshot('trace-shipment',M,57,CW,99)
 text('已出货不等于质检放行；质量结论与放行依据需单独核对。',M,39,8.5,MUTED)
 c.showPage()

 # 8 - actual, readable cost detail and next step.
 page(8,'真实界面：完整成本有明细可核对。','查看实际归集的材料、人工、包装、设备及其他成本，并核对合格产出和成本分母。')
 screenshot('cost-detail',M,195,488,242)
 x=559;text('演示批次',x,421,11,GREEN,True)
 para('20盒合格产出<br/>完整成本128.33元<br/>单位成本6.42元/盒',x,397,W-M-x,17,INK,True,30)
 para('数值仅用于说明核对方式。成本口径、费用归集和缺项处理，需要按本厂业务确认。',x,282,W-M-x,10.5)
 fill(INK);c.roundRect(M,66,CW,105,10,fill=1,stroke=0)
 text('从你最有代表性的产品聊起。',M+20,135,20,'#ffffff',True)
 text('带上已有产品与工艺资料，共同确定首批范围、配合事项和验收结果。',M+20,109,11,'#d5e4db')
 text('cretaceousfuture.com',M+20,85,10,'#72e3b2');text('预约演示：stevenj4xie@gmail.com',M+270,85,10,'#72e3b2')
 c.linkURL('https://cretaceousfuture.com',(M+20,78,M+230,100),relative=0);c.linkURL('mailto:stevenj4xie@gmail.com?subject=预约对话建厂演示',(M+270,78,W-M-20,100),relative=0)
 c.showPage();c.save()
 public=ROOT/'platform/downloads/cretas-factory-onboarding.pdf';public.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(args.output,public)
 print(json.dumps({'status':'CREATED','pages':8,'output':str(args.output),'publicCopy':str(public),'sha256':hashlib.sha256(args.output.read_bytes()).hexdigest()},ensure_ascii=False))
if __name__=='__main__':main()
