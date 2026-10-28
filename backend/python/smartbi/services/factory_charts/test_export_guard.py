import base64,io
import pytest
from PIL import Image
from fastapi import FastAPI
from fastapi.testclient import TestClient
from httpx import ASGITransport,AsyncClient
from smartbi.api import financial_dashboard as api
from . import export_guard as G

def app(role='factory_super_admin',factory='F006'):
    result=FastAPI()
    class Identity:
        def __init__(self,app):self.app=app
        async def __call__(self,scope,receive,send):
            scope.setdefault('state',{}).update(factory_id=factory,role=role)
            return await self.app(scope,receive,send)
    result.add_middleware(Identity)
    result.include_router(api.router)
    return result

@pytest.mark.parametrize('endpoint',['excel','pdf','ppt'])
@pytest.mark.parametrize('role,factory,status',[(None,'F006',401),('warehouse_worker','F006',403),('factory_super_admin',None,403)])
def test_real_export_identity_precedes_invalid_json(endpoint,role,factory,status):
    assert TestClient(app(role,factory)).post('/export-'+endpoint,content=b'INVALID JSON').status_code==status

@pytest.mark.parametrize('endpoint',['excel','pdf','ppt'])
def test_real_export_limits_before_generation(endpoint):
    c=TestClient(app())
    assert c.post('/export-'+endpoint,content=b'{}',headers={'content-length':str(G.MAX_BODY+1)}).status_code==413
    assert c.post('/export-'+endpoint,json={'chart_titles':{str(i):'a' for i in range(101)}}).status_code==422
    assert c.post('/export-'+endpoint,json={'analysis_results':{'a':'x'*20001}}).status_code==422
    assert c.post('/export-'+endpoint,json={'chart_titles':{'a':'x'*301}}).status_code==422

@pytest.mark.asyncio
async def test_chunked_stream_limit_without_content_length(monkeypatch):
    monkeypatch.setattr(G,'MAX_BODY',10)
    async def stream():
        yield b'123456'
        yield b'123456'
        raise AssertionError('must reject before reading more')
    async with AsyncClient(transport=ASGITransport(app=app()),base_url='http://test') as c:
        assert (await c.post('/export-pdf',content=stream())).status_code==413

def png():
    output=io.BytesIO();Image.new('RGB',(20,20)).save(output,format='PNG')
    return output.getvalue()

def test_single_and_total_image_budgets_and_real_header(monkeypatch):
    raw=png();encoded='data:image/png;base64,'+base64.b64encode(raw).decode()
    assert G.validate_export(None,{'chart_images':{'x':encoded}})
    with pytest.raises(ValueError):G.validate_export(None,{'chart_images':{'x':'data:image/png;base64,YmFk'}})
    monkeypatch.setattr(G,'MAX_IMAGE',len(raw)-1)
    with pytest.raises(ValueError,match='单张'):G.validate_export(None,{'chart_images':{'x':encoded}})
    monkeypatch.setattr(G,'MAX_IMAGE',2*len(raw));monkeypatch.setattr(G,'MAX_IMAGES',len(raw))
    with pytest.raises(ValueError,match='总量'):G.validate_export(None,{'chart_images':{'x':encoded,'y':encoded}})

def test_table_row_column_and_global_cell_limits():
    c=TestClient(app())
    for table in ({'headers':['x'],'rows':[[1]]*10001}, {'headers':['x']*101,'rows':[[1]]},
                  {'headers':['x'],'rows':[[1]*101]}):
        assert c.post('/export-excel',json={'charts':[{'tableData':table}]}).status_code==422
    charts=[{'tableData':{'headers':['x']*100,'rows':[[1]*100]*600}}]*2
    assert c.post('/export-excel',json={'charts':charts}).status_code==422

def test_total_decoded_pixels_rejected_by_real_pdf_route(monkeypatch):
    encoded='data:image/png;base64,'+base64.b64encode(png()).decode()
    monkeypatch.setattr(G,'MAX_PIXELS',500)
    response=TestClient(app()).post('/export-pdf',json={'chart_images':{'a':encoded,'b':encoded}})
    assert response.status_code==422 and '总像素' in response.text
