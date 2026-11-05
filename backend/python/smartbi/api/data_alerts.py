"""Shared, authenticated anomaly analysis for Excel and connector/API datasets."""
from typing import Any,Literal,Optional
from fastapi import APIRouter,HTTPException,Request
from pydantic import BaseModel,Field,ConfigDict,model_validator
from common.tenant_identity import require_same_factory
from smartbi.services.dataset_alerts import analyze_dataset
from smartbi.services.alert_datasets import load_upload,for_role,for_request,can_view_details
from smartbi.services.alert_local_ai import enrich

router=APIRouter(prefix='/api/smartbi/data-alerts',tags=['SmartBI Data Alerts'])

def identity(request,claimed=None):
    if getattr(request.state,'auth_method',None) not in ('jwt','internal'):
        raise HTTPException(401,'Authenticated tenant identity required')
    return require_same_factory(request,claimed)

class DatasetRequest(BaseModel):
    model_config=ConfigDict(extra='forbid')
    rows:list[dict[str,Any]]=Field(default_factory=list,max_length=10000)
    factory_id:Optional[str]=None
    source_type:Literal['excel','api','connector','database','sales','finance']='api'
    source_id:str=Field(...,min_length=1,max_length=200)
    field_mapping:Optional[dict[str,str]]=None
    columns:Optional[list[dict[str,Any]]]=Field(None,max_length=500)
    total_rows:Optional[int]=Field(None,ge=0)
    dataset_kind:Literal['generic','production_cost']='generic'
    with_ai:bool=False

    @model_validator(mode='after')
    def bounded_values(self):
        import json
        try:
            if len(json.dumps({'rows':self.rows,'columns':self.columns,'mapping':self.field_mapping},ensure_ascii=False,allow_nan=False).encode('utf-8'))>10_000_000:
                raise HTTPException(413,'数据超过10MB，请使用已保存的数据集分析')
        except (TypeError,ValueError,RecursionError):raise HTTPException(422,'数据包含不可解析或非有限数字') from None
        if self.field_mapping and len(self.field_mapping)>200:raise ValueError('字段映射超过200项')
        if not self.source_id.strip():raise ValueError('来源标识不能为空')
        return self

@router.post('/analyze')
async def analyze(payload:DatasetRequest,request:Request):
    factory=identity(request,payload.factory_id)
    role=getattr(request.state,'role',None)
    if payload.with_ai and not can_view_details(role):raise HTTPException(403,'当前角色无明细AI分析权限')
    import asyncio
    report=await asyncio.to_thread(analyze_dataset,payload.rows,factory_id=factory,source_type=payload.source_type,source_id=payload.source_id,
                                   field_mapping=payload.field_mapping,columns=payload.columns,total_rows=payload.total_rows,dataset_kind=payload.dataset_kind)
    if payload.with_ai:await enrich(report,getattr(request.state,'user_id',None))
    return {'success':True,'data':for_role(report,role),'message':'规则已完成；提醒待业务核对'}

async def upload_report(upload_id,request,with_ai=False):
    factory=identity(request,request.query_params.get('factory_id'))
    role=getattr(request.state,'role',None)
    if with_ai and not can_view_details(role):raise HTTPException(403,'当前角色无明细AI分析权限')
    try:
        import asyncio
        report=await asyncio.to_thread(load_upload,factory,upload_id)
    except HTTPException:raise
    except Exception:raise HTTPException(503,'数据集暂不可读，请核对数据库连接及上传状态') from None
    report=await for_request(report,request,stored=True)
    if with_ai and report.get('analysisStatus')!='restricted':await enrich(report,getattr(request.state,'user_id',None))
    return {'success':True,'data':report,'message':'数据集异常核对完成'}

@router.get('/uploads/{upload_id}')
async def get_upload_alerts(upload_id:int,request:Request):
    return await upload_report(upload_id,request)

@router.post('/uploads/{upload_id}/local-review')
async def review_upload(upload_id:int,request:Request):
    return await upload_report(upload_id,request,True)
