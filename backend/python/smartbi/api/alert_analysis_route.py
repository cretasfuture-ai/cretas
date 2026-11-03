"""Attach the same deterministic report to existing inline analysis responses.

Only authenticated tenant requests receive scoped alerts. Legacy endpoint
behaviour is preserved; this hook never invokes a model or reads other records.
"""
import asyncio
import json
from fastapi import Request
from fastapi.routing import APIRoute
from starlette.responses import JSONResponse
from smartbi.services.dataset_alerts import analyze_dataset
from smartbi.services.alert_datasets import for_role

class AlertAnalysisRoute(APIRoute):
    def get_route_handler(self):
        handler=super().get_route_handler()
        async def wrapped(request:Request):
            response=await handler(request)
            factory=getattr(request.state,'factory_id',None)
            if (request.method!='POST' or not factory or getattr(request.state,'auth_method',None) not in ('jwt','internal')
                    or response.status_code>=400 or not hasattr(response,'body')):return response
            try:
                payload=await request.json()
                rows=payload.get('data') if isinstance(payload,dict) else None
                if not isinstance(rows,list) or not all(isinstance(r,dict) for r in rows):return response
                body=json.loads(response.body)
                if not isinstance(body,dict):return response
                path=request.url.path
                source_type='finance' if '/finance/' in path else 'sales' if '/sales/' in path else 'api'
                report=await asyncio.to_thread(analyze_dataset,rows,factory_id=str(factory),source_type=source_type,
                                                source_id=path,field_mapping=payload.get('fieldMapping'),dataset_kind='generic')
                body['dataAlerts']=for_role(report,getattr(request.state,'role',None))
                headers={k:v for k,v in response.headers.items() if k.lower() not in ('content-length','content-type')}
                return JSONResponse(body,status_code=response.status_code,headers=headers)
            except Exception:
                # Existing results remain readable; do not claim alerts were calculated.
                try:
                    body=json.loads(response.body)
                    if isinstance(body,dict):
                        body['dataAlerts']={'status':'unavailable','message':'数据异常检查未完成，请使用数据集核对入口重试'}
                        headers={k:v for k,v in response.headers.items() if k.lower() not in ('content-length','content-type')}
                        return JSONResponse(body,status_code=response.status_code,headers=headers)
                except Exception:pass
                return response
        return wrapped
