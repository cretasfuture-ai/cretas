"""Catalog checks shared by the local SmartBI loader and read-only adapter."""
def validate(cur, role='blacklake_bi_reader'):
    cur.execute('SELECT rolsuper,rolbypassrls,rolcreatedb,rolcreaterole,rolreplication,rolcanlogin,rolinherit FROM pg_roles WHERE rolname=%s',(role,))
    flags=cur.fetchone()
    if not flags or any(flags[:5]) or not flags[5] or flags[6]:
        raise RuntimeError('SMARTBI_READER_ROLE_UNSAFE')
    cur.execute('SELECT oid FROM pg_roles WHERE rolname=%s',(role,))
    role_oid=cur.fetchone()[0]
    cur.execute('SELECT count(*) FROM pg_auth_members WHERE member=%s OR roleid=%s',(role_oid,role_oid))
    if cur.fetchone()[0]: raise RuntimeError('SMARTBI_READER_MEMBERSHIP_UNSAFE')
    cur.execute("SELECT c.relname,c.relrowsecurity,c.relforcerowsecurity,pg_get_userbyid(c.relowner) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='blacklake_bi' AND c.relkind='r'")
    tables=cur.fetchall()
    if {r[0] for r in tables}!={'dataset','record'} or any(not a or not b or owner==role for _,a,b,owner in tables):
        raise RuntimeError('SMARTBI_SCHEMA_RLS_UNSAFE')
    expression="((tenant_id = current_setting('blacklake.tenant_id'::text, true)) AND (source_factory_id = current_setting('blacklake.source_factory_id'::text, true)))"
    base={key:('text','NO') for key in ('tenant_id','source_factory_id','run_id')}
    expected={'dataset':dict(base,metadata=('jsonb','NO'),loaded_at=('timestamp with time zone','NO')),
              'record':dict(base,resource=('text','NO'),external_id=('text','NO'),content_hash=('text','NO'),payload=('jsonb','NO'))}
    for table in ('dataset','record'):
        qualified='blacklake_bi.'+table
        cur.execute("SELECT column_name,data_type,is_nullable FROM information_schema.columns WHERE table_schema='blacklake_bi' AND table_name=%s",(table,))
        if {name:(kind,nullable) for name,kind,nullable in cur.fetchall()}!=expected[table]:
            raise RuntimeError('SMARTBI_SCHEMA_COLUMNS_UNSAFE')
        cur.execute('SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid=%s::regclass AND contype IN (\'p\',\'f\') ORDER BY contype',(qualified,))
        constraints=[r[0] for r in cur.fetchall()]
        wanted=['PRIMARY KEY (tenant_id, source_factory_id, run_id)'] if table=='dataset' else [
            'FOREIGN KEY (tenant_id, source_factory_id, run_id) REFERENCES blacklake_bi.dataset(tenant_id, source_factory_id, run_id)',
            'PRIMARY KEY (tenant_id, source_factory_id, run_id, resource, external_id)']
        if constraints!=wanted: raise RuntimeError('SMARTBI_SCHEMA_KEYS_UNSAFE')
        cur.execute("SELECT a.grantee,a.privilege_type,a.is_grantable FROM pg_class c CROSS JOIN LATERAL aclexplode(COALESCE(c.relacl,acldefault('r',c.relowner))) a WHERE c.oid=%s::regclass AND a.grantee<>c.relowner",(qualified,))
        if cur.fetchall()!=[(role_oid,'SELECT',False)]:
            raise RuntimeError('SMARTBI_TABLE_EXTRA_GRANTEE')
        cur.execute('SELECT polname,pg_get_expr(polqual,polrelid),pg_get_expr(polwithcheck,polrelid),polcmd,polroles,polpermissive FROM pg_policy WHERE polrelid=%s::regclass',(qualified,))
        if cur.fetchall()!=[('scope',expression,None,'*',[0],True)]:
            raise RuntimeError('SMARTBI_SCOPE_POLICY_UNSAFE')
        for permission in ('SELECT','INSERT','UPDATE','DELETE','TRUNCATE','REFERENCES','TRIGGER'):
            cur.execute('SELECT has_table_privilege(%s,%s,%s)',(role,qualified,permission))
            if cur.fetchone()[0] != (permission=='SELECT'):
                raise RuntimeError('SMARTBI_READER_GRANTS_UNSAFE')
    cur.execute("SELECT has_schema_privilege(%s,'blacklake_bi','USAGE'),has_schema_privilege(%s,'blacklake_bi','CREATE')",(role,role))
    if cur.fetchone()!=(True,False): raise RuntimeError('SMARTBI_SCHEMA_GRANTS_UNSAFE')
    cur.execute("SELECT count(*) FROM pg_namespace n CROSS JOIN LATERAL aclexplode(COALESCE(n.nspacl,acldefault('n',n.nspowner))) a WHERE n.nspname='blacklake_bi' AND a.grantee=0")
    if cur.fetchone()[0]: raise RuntimeError('SMARTBI_SCHEMA_PUBLIC_GRANTS_UNSAFE')
