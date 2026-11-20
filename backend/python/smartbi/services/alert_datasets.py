"""Read existing, tenant-owned SmartBI records; never read a connector's secrets."""
from copy import deepcopy
from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES
from .dataset_alerts import analyze_dataset, MAX_ROWS

def can_view_details(role):
    return role in PRICE_VIEW_ROLES

def for_role(report,role):
    if can_view_details(role):return report
    masked=deepcopy(report)
    masked['items']=[]
    masked.pop('analysisMetrics',None)
    masked['summary'].update(alerts=0,byType={},byFamily={})
    masked['runId']='restricted'
    masked['analysisStatus']='restricted'
    masked['capabilities']['cost']={'status':'restricted','available':False,'reason':'当前角色不展示成本及明细'}
    masked['capabilities']['costMissingFields']=[]
    masked['capabilities']['quality']={'status':'restricted','available':False}
    masked['ai']={'status':'restricted','message':'当前角色仅可查看规则核对提示'}
    return masked

async def for_request(report,request,*,stored=False):
    role=getattr(request.state,'role',None)
    if not can_view_details(role):return for_role(report,role)
    if stored and (report.get('source') or {}).get('type') in ('finance','sales'):
        from smartbi.services.factory_charts.finance_auth import full_factory_finance_access
        factory=getattr(request.state,'factory_id',None)
        if not await full_factory_finance_access(request,str(factory)):
            masked=for_role(report,None)
            masked['ai']={'status':'restricted','message':'财务数据范围授权尚未确认，暂不展示数据派生结论'}
            return masked
    # A client already supplies its own inline data. Stored factory records
    # require the existing manufacturing-cost authority before revealing any
    # cost-derived candidates, even when a general price-view role is present.
    cost=report.get('capabilities',{}).get('cost',{})
    if stored and cost.get('hasCostColumns'):
        from smartbi.services.factory_charts.cost_scope import resolve_cost_scope
        factory=getattr(request.state,'factory_id',None)
        authority=await resolve_cost_scope(request,str(factory),[])
        if not authority.is_full:
            masked=for_role(report,None)
            masked['items']=[]
            masked['summary'].update(alerts=0,byType={},byFamily={})
            masked['analysisStatus']='restricted'
            masked['ai']={'status':'restricted','message':'成本或工艺保密授权尚未确认；不显示成本派生提醒，请由授权负责人核对'}
            masked['capabilities']['quality']['status']='authorization_required'
            return masked
    return report

def load_upload(factory_id,upload_id):
    from fastapi import HTTPException
    from sqlalchemy import text
    from smartbi.database.connection import get_db_context
    from smartbi.database.models import SmartBiPgExcelUpload,SmartBiDynamicData,SmartBiPgFieldDefinition
    with get_db_context() as db:
        db.execute(text('SET TRANSACTION READ ONLY'))
        upload=db.query(SmartBiPgExcelUpload).filter(SmartBiPgExcelUpload.id==upload_id,SmartBiPgExcelUpload.factory_id==factory_id).first()
        if upload is None:raise HTTPException(404,'upload not found')
        if upload.upload_status!='COMPLETED':raise HTTPException(409,'上传尚未完成，暂不能分析')
        query=db.query(SmartBiDynamicData).filter(SmartBiDynamicData.factory_id==factory_id,SmartBiDynamicData.upload_id==upload_id)
        total=query.count()
        entities=query.order_by(SmartBiDynamicData.row_index,SmartBiDynamicData.id).limit(MAX_ROWS+1).all()
        fields=db.query(SmartBiPgFieldDefinition).filter(SmartBiPgFieldDefinition.upload_id==upload_id).all()
        context=upload.context_info or {}
        source_type=context.get('sourceType','excel')
        if source_type not in ('excel','api','connector','database','sales','finance'):source_type='excel'
        domain=str(upload.detected_table_type or '').lower()
        kind='production_cost' if domain in ('production','production_cost','work_order','生产工单','工单成本') else 'generic'
        if 'finance' in domain or '财务' in domain:source_type='finance'
        elif 'sales' in domain or '销售' in domain:source_type='sales'
        report=analyze_dataset([r.row_data for r in entities],factory_id=factory_id,source_type=source_type,
                               source_id='upload:'+str(upload_id),columns=[f.to_dict() for f in fields],total_rows=total,dataset_kind=kind)
        for item in report['items']:
            for ref in item.get('sourceRefs',[]):
                position=ref['rowNumber']-1
                if 0<=position<len(entities):
                    ref.update(recordId=entities[position].id,sourceRowIndex=entities[position].row_index)
        report['source']['uploadId']=upload_id
        return report
