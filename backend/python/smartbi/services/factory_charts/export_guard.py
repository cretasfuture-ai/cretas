"""Bounded export resources; run identity and streaming size checks before JSON."""
import base64
import binascii
from io import BytesIO
from fastapi import HTTPException, Request
from fastapi.routing import APIRoute
from PIL import Image, UnidentifiedImageError
from smartbi_compat._rbac_role import ANALYTICS_READ_ROLES

MAX_BODY=20*1024*1024
MAX_IMAGE=2*1024*1024
MAX_IMAGES=12*1024*1024
MAX_PIXELS=24_000_000

def validate_export(cls,values):
    if not isinstance(values,dict): raise ValueError('导出参数必须是对象')
    for key in ('charts','chart_images','chart_titles','analysis_results'):
        collection=values.get(key) or []
        if not isinstance(collection,(list,dict)) or len(collection)>100:
            raise ValueError('导出图表或分析数量不能超过100')
    images=values.get('chart_images') or {}
    if not isinstance(images,dict): raise ValueError('图片必须按图表标识提供')
    total_image=0
    total_pixels=0
    for key,value in images.items():
        if not isinstance(key,str) or len(key)>300 or not isinstance(value,str): raise ValueError('图片标识或内容无效')
        if len(value)>MAX_IMAGE*4//3+128: raise ValueError('单张图片超过2MB')
        if not value: continue
        encoded=value.split(',',1)[1] if value.startswith(('data:image/png;base64,','data:image/jpeg;base64,')) else value
        try:
            raw=base64.b64decode(encoded,validate=True)
            if len(raw)>MAX_IMAGE: raise ValueError('单张图片超过2MB')
            total_image+=len(raw)
            if total_image>MAX_IMAGES: raise ValueError('图片总量超过12MB')
            with Image.open(BytesIO(raw)) as image:
                if image.format not in ('PNG','JPEG') or image.width*image.height>16_000_000:
                    raise ValueError('图片须为PNG/JPEG且不超过1600万像素')
                total_pixels+=image.width*image.height
                if total_pixels>MAX_PIXELS: raise ValueError('导出图片总像素超过2400万')
        except (binascii.Error,UnidentifiedImageError,OSError,Image.DecompressionBombError) as error:
            raise ValueError('图片数据无效') from error
    total_text=0
    def walk(node,depth=0):
        nonlocal total_text
        if depth>12: raise ValueError('导出参数嵌套过深')
        if isinstance(node,str):
            if len(node)>20000: raise ValueError('单段文本超过20000字符')
            total_text+=len(node)
            if total_text>250000: raise ValueError('导出文本超过250000字符')
        elif isinstance(node,dict):
            for key,value in node.items():
                if len(str(key))>300: raise ValueError('导出字段名超过300字符')
                if key=='chart_images': continue
                if key in ('title','report_title','company_name','period_label') and isinstance(value,str) and len(value)>300:
                    raise ValueError('导出标题超过300字符')
                walk(value,depth+1)
        elif isinstance(node,list):
            if len(node)>10000: raise ValueError('导出列表超过10000项')
            for value in node: walk(value,depth+1)
    walk(values)
    titles=values.get('chart_titles') or {}
    if not isinstance(titles,dict) or any(not isinstance(v,str) or len(v)>300 for v in titles.values()):
        raise ValueError('图表标题超过300字符')
    cells=0
    for chart in values.get('charts') or []:
        if not isinstance(chart,dict): raise ValueError('图表参数无效')
        table=chart.get('tableData') or []
        rows=table.get('rows',[]) if isinstance(table,dict) else table
        headers=table.get('headers',table.get('columns',[])) if isinstance(table,dict) else []
        if not isinstance(rows,list) or len(rows)>10000 or len(headers)>100: raise ValueError('表格超过10000行或100列')
        cells+=len(headers)
        for row in rows:
            if isinstance(row,dict):
                content=row.get('values')
                width=1+len(content) if isinstance(content,list) else len(row)
            elif isinstance(row,list): width=len(row)
            else: width=1
            if width>100: raise ValueError('表格超过100列')
            cells+=width
            if cells>100000: raise ValueError('导出表格总单元格超过100000')
    return values

class ExportGuardRoute(APIRoute):
    def get_route_handler(self):
        handler=super().get_route_handler()
        async def guarded(request:Request):
            if request.url.path.endswith(('/export-excel','/export-pdf','/export-ppt')):
                role=getattr(request.state,'role',None)
                if not role: raise HTTPException(401,'需要登录后导出')
                if role not in ANALYTICS_READ_ROLES: raise HTTPException(403,'当前身份没有经营分析查看权限')
                factory=getattr(request.state,'factory_id',None)
                if not isinstance(factory,str) or not factory.strip(): raise HTTPException(403,'导出需要有效的工厂身份')
                length=request.headers.get('content-length')
                if length and (not length.isdigit() or int(length)>MAX_BODY): raise HTTPException(413,'导出请求超过20MB')
                body=bytearray()
                async for chunk in request.stream():
                    if len(body)+len(chunk)>MAX_BODY: raise HTTPException(413,'导出请求超过20MB')
                    body.extend(chunk)
                request._body=bytes(body)
            return await handler(request)
        return guarded
