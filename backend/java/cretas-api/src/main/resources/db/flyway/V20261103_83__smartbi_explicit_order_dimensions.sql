-- Reuse the Canvas sales_order capture path. These nullable fields carry only
-- explicitly entered dimensions, never customer-name/payment heuristics.
SET LOCAL lock_timeout = '3s';
ALTER TABLE sales_orders
    ADD COLUMN IF NOT EXISTS cf_smartbi_sales_channel TEXT,
    ADD COLUMN IF NOT EXISTS cf_smartbi_department_id TEXT;

-- Pilot definitions are factory-scoped. No existing definition/value is
-- overwritten; other factories opt in through the existing configuration UI.
INSERT INTO canvas_dynamic_field
    (factory_id,module_code,field_code,field_type,label,config,sort_order,status,column_name)
SELECT 'F006','sales_order','smartbi_sales_channel','TEXT','销售渠道（实际渠道）',
       '{"required":false,"smartbiSemantic":"sales_channel.v1","maxLength":80}'::jsonb,
       80,'ACTIVE','cf_smartbi_sales_channel'
WHERE EXISTS (SELECT 1 FROM factories WHERE id='F006')
ON CONFLICT (factory_id,module_code,field_code) DO NOTHING;

INSERT INTO canvas_dynamic_field
    (factory_id,module_code,field_code,field_type,label,config,sort_order,status,column_name)
SELECT 'F006','sales_order','smartbi_department_id','SELECT','责任部门',
       jsonb_build_object('required',false,'smartbiSemantic','department_id.v1',
           'options',COALESCE((SELECT jsonb_agg(jsonb_build_object('value',d.id::text,'label',d.name) ORDER BY d.name,d.id)
                              FROM departments d WHERE d.factory_id='F006' AND d.is_active=true AND d.deleted_at IS NULL), '[]'::jsonb)),
       81,'ACTIVE','cf_smartbi_department_id'
WHERE EXISTS (SELECT 1 FROM factories WHERE id='F006')
ON CONFLICT (factory_id,module_code,field_code) DO NOTHING;
