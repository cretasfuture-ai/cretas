"""Run the isolated SmartBI source adapter without restarting shared test services."""
import os
import sys
import asyncio
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse

from auth_middleware import JWTAuthMiddleware
from .smartbi_store import settings


def create_app():
    # Match main.py's legacy SmartBI package bootstrap for standalone test use.
    smartbi_path=str(Path(__file__).resolve().parents[1]/'smartbi')
    if smartbi_path not in sys.path:sys.path.insert(0,smartbi_path)
    from .smartbi_api import router
    path=Path(os.environ['LOCALAPPDATA'])/'Cretas/Blacklake/smartbi-test.dpapi'
    configured=settings(path)
    if len(configured.get('jwt_secret',''))<32:
        raise RuntimeError('TEST_JWT_KEY_REQUIRED')
    os.environ['BLACKLAKE_SMARTBI_CONFIG']=str(path)
    os.environ['BLACKLAKE_ALERT_FOLLOWUP_DB']=str(path.resolve().parent/'alert-followups.sqlite3')
    os.environ['BLACKLAKE_PERIOD_REPORT_DB']=str(path.resolve().parent/'period-reports.sqlite3')
    os.environ.setdefault('BLACKLAKE_PERIOD_SCHEDULER_ENABLED','1')
    # Dedicated current-policy test Java; shared legacy 11010 stays untouched.
    os.environ['JAVA_API_BASE_URL']='http://127.0.0.1:11210'
    os.environ['SMARTBI_ALERT_AI_TRANSPORT']='ssh'
    host=os.environ.get('BLACKLAKE_LOCAL_AI_SSH_HOST','canada-mini')
    if host not in ('canada-mini','aurora-mini'):
        raise RuntimeError('LOCAL_AI_APPROVED_HOST_REQUIRED')
    os.environ['SMARTBI_ALERT_AI_SSH_HOST']=host
    os.environ['SMARTBI_ALERT_AI_MODEL']='qwen3.8:27b'
    os.environ['SMARTBI_LOCAL_ANALYST_ENABLED']='1'
    os.environ['SMARTBI_ALERT_AI_STATE_FILE']=str((Path(os.environ['LOCALAPPDATA'])/'Cretas/SmartBIAlerts/local-ai-state.sqlite3').resolve())
    @asynccontextmanager
    async def lifespan(app):
        from .period_runtime import schedule_loop, ensure_worker, stop_workers
        ensure_worker(configured)
        scheduler=asyncio.create_task(schedule_loop(configured)) if os.environ.get('BLACKLAKE_PERIOD_SCHEDULER_ENABLED')=='1' else None
        from .live_api import analysis_loop
        live_task=asyncio.create_task(analysis_loop(configured)) if os.environ.get('BLACKLAKE_LIVE_CONFIG') else None
        try:
            yield
        finally:
            if scheduler is not None:
                scheduler.cancel()
                await asyncio.gather(scheduler,return_exceptions=True)
            if live_task is not None:
                live_task.cancel()
                await asyncio.gather(live_task,return_exceptions=True)
            await stop_workers()

    app=FastAPI(title='SmartBI · 六膳门黑湖只读测试',docs_url=None,redoc_url=None,openapi_url=None,lifespan=lifespan)
    app.add_middleware(JWTAuthMiddleware,jwt_secret=configured['jwt_secret'],enabled=True)
    app.include_router(router,prefix='/api/smartbi/blacklake',tags=['Blacklake SmartBI'])

    @app.get('/health')
    def health():
        return {'success':True,'environment':'test','source':'blacklake','businessWritesEnabled':False,'followupMetadataWritesEnabled':True}

    @app.exception_handler(HTTPException)
    async def http_error(request,exc):
        return JSONResponse(status_code=exc.status_code,content={'success':False,'data':None,'message':exc.detail})

    return app


if __name__=='__main__':
    import uvicorn
    uvicorn.run(create_app(),host='127.0.0.1',port=8093,access_log=False)
