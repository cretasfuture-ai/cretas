"""Isolated shared-alert test app and real-Excel/PG fixtures; no production I/O."""
import json
import os
from pathlib import Path
import secrets
import sys
import uuid

ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT/'smartbi'))

def directory():
    return (Path(os.environ['LOCALAPPDATA'])/'Cretas/SmartBIAlerts').resolve()

def prepare():
    import psycopg2
    from psycopg2 import sql
    from psycopg2.extensions import parse_dsn,make_dsn
    from sqlalchemy import create_engine
    from sqlalchemy.engine import URL
    from sqlalchemy.orm import Session
    from openpyxl import Workbook,load_workbook
    from blacklake.secrets import load_local_secret,save_local_secret
    from smartbi.database.models import SmartBiPgExcelUpload,SmartBiDynamicData,SmartBiPgFieldDefinition
    source=Path(os.environ['LOCALAPPDATA'])/'Cretas/Blacklake'
    original=load_local_secret(source/'test-database.dpapi')
    auth=load_local_secret(source/'smartbi-test.dpapi')
    out=directory();out.mkdir(parents=True,exist_ok=True)
    config_path=out/'testenv.dpapi'
    if config_path.exists():
        saved=load_local_secret(config_path)
        if saved.get('fixtureVersion')==2:
            print(json.dumps({'prepared':True,'reused':True,'fixtureVersion':2}));return
        if not saved['database'].startswith('smartbi_alerts_'):raise RuntimeError('SYNTHETIC_DATABASE_REQUIRED')
        target={**parse_dsn(original['admin_dsn']),'dbname':saved['database']}
        import psycopg2.extras
        connection=psycopg2.connect(make_dsn(**target))
        rows=[{'流水号':'SYNTH-1','测量值':100},{'流水号':'SYNTH-2','测量值':None},{'流水号':'SYNTH-3','测量值':'待核'},{'流水号':'SYNTH-1','测量值':100}]
        original_rows=saved['finance_rows']
        with connection:
            with connection.cursor() as cur:
                cur.execute("SELECT id FROM smart_bi_pg_excel_uploads WHERE id IN (101,201) AND context_info->>'synthetic'='true'")
                if len(cur.fetchall())!=2:raise RuntimeError('SYNTHETIC_FIXTURE_IDENTITY_REQUIRED')
                for upload_id,factory in ((101,saved['tenant_id']),(201,'SYNTH_FOREIGN')):
                    cur.execute('DELETE FROM smart_bi_dynamic_data WHERE upload_id=%s AND factory_id=%s',(upload_id,factory))
                    cur.execute('DELETE FROM smart_bi_pg_field_definitions WHERE upload_id=%s',(upload_id,))
                    cur.execute("UPDATE smart_bi_pg_excel_uploads SET detected_table_type='operational' WHERE id=%s AND factory_id=%s",(upload_id,factory))
                    for i,row in enumerate(rows):cur.execute('INSERT INTO smart_bi_dynamic_data (factory_id,upload_id,row_index,row_data) VALUES (%s,%s,%s,%s)',(factory,upload_id,i,psycopg2.extras.Json(row)))
                    for col in rows[0]:cur.execute('INSERT INTO smart_bi_pg_field_definitions (upload_id,original_name,standard_name,field_type,is_measure,is_dimension,agg_strategy) VALUES (%s,%s,%s,%s,%s,%s,%s)',(upload_id,col,col,'number' if col=='测量值' else 'text',col=='测量值',col!='测量值','SUM'))
                cur.execute("INSERT INTO smart_bi_pg_excel_uploads (id,factory_id,file_name,upload_status,detected_table_type,row_count,column_count,context_info) VALUES (104,%s,'synthetic-finance-scope.xlsx','COMPLETED','finance',4,2,%s)",(saved['tenant_id'],psycopg2.extras.Json({'synthetic':True,'sourceType':'finance'})))
                for i,row in enumerate(original_rows):cur.execute('INSERT INTO smart_bi_dynamic_data (factory_id,upload_id,row_index,row_data) VALUES (%s,104,%s,%s)',(saved['tenant_id'],i,psycopg2.extras.Json(row)))
                for col in original_rows[0]:cur.execute('INSERT INTO smart_bi_pg_field_definitions (upload_id,original_name,standard_name,field_type,is_measure,is_dimension,agg_strategy) VALUES (104,%s,%s,%s,%s,%s,%s)',(col,col,'number' if col=='收入' else 'text',col=='收入',col!='收入','SUM'))
        connection.close()
        book=out/'synthetic-quality.xlsx'
        if book.exists():book.rename(out/'synthetic-quality-v1-finance.xlsx')
        workbook=Workbook();sheet=workbook.active;sheet.title='合成运营质量';sheet.append(list(rows[0]))
        for row in rows:sheet.append(list(row.values()))
        workbook.save(book)
        check=load_workbook(book,data_only=True,read_only=True);values=list(check.active.values);check.close()
        assert [dict(zip(values[0],r)) for r in values[1:]]==rows
        saved.update(finance_rows=rows,fixtureVersion=2,financial_authority_upload=104)
        save_local_secret(config_path,saved)
        print(json.dumps({'prepared':True,'fixtureVersion':2,'syntheticRows':15,'sourceBusinessWrites':0}));return
    name='smartbi_alerts_'+uuid.uuid4().hex[:10];reader=name+'_reader';password=secrets.token_urlsafe(28)
    admin=psycopg2.connect(original['admin_dsn']);admin.autocommit=True
    try:
        with admin.cursor() as cur:
            cur.execute(sql.SQL('CREATE DATABASE {}').format(sql.Identifier(name)))
            cur.execute(sql.SQL('CREATE ROLE {} LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS PASSWORD %s').format(sql.Identifier(reader)),(password,))
    finally:admin.close()
    target={**parse_dsn(original['admin_dsn']),'dbname':name}
    url=URL.create('postgresql+psycopg2',username=target['user'],password=target['password'],host=target['host'],port=int(target['port']),database=name)
    engine=create_engine(url)
    tables=[SmartBiPgExcelUpload.__table__,SmartBiDynamicData.__table__,SmartBiPgFieldDefinition.__table__]
    SmartBiPgExcelUpload.metadata.create_all(engine,tables=tables)
    headers=['流水号','收入'];finance_rows=[['SYNTH-1',100],['SYNTH-2',None],['SYNTH-3','待核'],['SYNTH-1',100]]
    workbook=Workbook();sheet=workbook.active;sheet.title='合成财务质量';sheet.append(headers)
    for row in finance_rows:sheet.append(row)
    book=out/'synthetic-quality.xlsx';workbook.save(book)
    # Real Excel deserialisation feeds the same persisted row model used by uploads.
    read=load_workbook(book,data_only=True,read_only=True);values=list(read.active.values);read.close()
    rows=[dict(zip(values[0],r)) for r in values[1:]]
    cost_rows=[{'工单编码':'SYNTH-W'+str(i),'产品代码':'SYNTH-P','产品名称':'合成产品','单位':'kg','成品良品量':10,'材料成本':v,'人工成本':10} for i,v in enumerate([100,110,200])]
    with Session(engine) as db:
        for upload_id,factory,kind,data in [(101,auth['tenant_id'],'finance',rows),(102,auth['tenant_id'],'production_cost',cost_rows),(201,'SYNTH_FOREIGN','finance',rows)]:
            db.add(SmartBiPgExcelUpload(id=upload_id,factory_id=factory,file_name='synthetic-quality.xlsx',sheet_name='合成验收',upload_status='COMPLETED',detected_table_type=kind,context_info={'sourceType':'excel','synthetic':True},row_count=len(data),column_count=len(data[0])))
            for i,row in enumerate(data):db.add(SmartBiDynamicData(factory_id=factory,upload_id=upload_id,row_index=i,row_data=row,sheet_name='合成验收'))
            for col in data[0]:
                numeric=col in ('收入','成品良品量','材料成本','人工成本')
                db.add(SmartBiPgFieldDefinition(upload_id=upload_id,original_name=col,standard_name=col,field_type='number' if numeric else 'text',is_measure=numeric,is_dimension=not numeric))
        db.commit()
    admin=psycopg2.connect(make_dsn(**target));admin.autocommit=True
    try:
        with admin.cursor() as cur:
            cur.execute(sql.SQL('GRANT CONNECT ON DATABASE {} TO {}').format(sql.Identifier(name),sql.Identifier(reader)))
            cur.execute(sql.SQL('GRANT USAGE ON SCHEMA public TO {}').format(sql.Identifier(reader)))
            cur.execute(sql.SQL('GRANT SELECT ON ALL TABLES IN SCHEMA public TO {}').format(sql.Identifier(reader)))
            cur.execute(sql.SQL('ALTER ROLE {} SET default_transaction_read_only=on').format(sql.Identifier(reader)))
    finally:admin.close();engine.dispose()
    config={k:auth[k] for k in ('jwt_secret','tenant_id','username','password')}
    config.update(database=name,db_host=target['host'],db_port=target['port'],db_user=reader,db_password=password,
                  finance_upload=101,cost_upload=102,foreign_upload=201,finance_rows=rows,cost_rows=cost_rows)
    save_local_secret(config_path,config)
    print(json.dumps({'prepared':True,'database':name,'synthetic':True,'sourceBusinessWrites':0,'uploads':3,'rows':11}))

def create_app():
    from blacklake.secrets import load_local_secret
    config=load_local_secret(directory()/'testenv.dpapi')
    os.environ.update(POSTGRES_ENABLED='true',POSTGRES_HOST=config['db_host'],POSTGRES_PORT=str(config['db_port']),POSTGRES_DB=config['database'],
                      POSTGRES_USER=config['db_user'],POSTGRES_PASSWORD=config['db_password'],JWT_SECRET=config['jwt_secret'],
                      JAVA_API_BASE_URL='http://127.0.0.1:11010',SMARTBI_ALERT_AI_TRANSPORT='ssh',SMARTBI_ALERT_AI_SSH_HOST='canada-mini',
                      SMARTBI_ALERT_AI_MODEL='qwen3.8:27b',SMARTBI_ALERT_AI_STATE_FILE=str(directory()/'local-ai-state.sqlite3'))
    from fastapi import FastAPI,HTTPException
    from fastapi.responses import JSONResponse
    from auth_middleware import JWTAuthMiddleware
    from smartbi.api.data_alerts import router
    from smartbi.api.analysis import router as analysis_router
    app=FastAPI(title='SmartBI shared alerts · isolated test',docs_url=None,redoc_url=None,openapi_url=None)
    app.add_middleware(JWTAuthMiddleware,jwt_secret=config['jwt_secret'],enabled=True)
    app.include_router(router);app.include_router(analysis_router,prefix='/api/analysis')
    @app.get('/health')
    def health():return {'success':True,'environment':'test','feature':'smartbi-shared-alerts','synthetic':True}
    @app.exception_handler(HTTPException)
    async def http_error(request,exc):return JSONResponse(status_code=exc.status_code,content={'success':False,'data':None,'message':exc.detail})
    return app

if __name__=='__main__':
    if '--prepare' in sys.argv:prepare()
    else:
        import uvicorn
        uvicorn.run(create_app(),host='127.0.0.1',port=8094,access_log=False)
