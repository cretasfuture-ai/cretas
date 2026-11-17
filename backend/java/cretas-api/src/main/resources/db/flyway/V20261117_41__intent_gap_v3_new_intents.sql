-- V20261117_41__intent_gap_v3_new_intents.sql
--
-- 意图蒸馏 v3 人工复核 (INTENT-DISTILL-20261111): 两个强模型都找不到合适意图、目录确实缺的 5 类, Steve 已拍板「缺的加上」。
-- 只新增绑定, 不改既有意图。写意图走现有写确认卡 (工具 WRITE + supportsPreview) 与 ToolRbacEnforcer 的权限码:
--   CUSTOMER_CREATE                         -> customer_create                (写, MEDIUM; sales/finance:read_write, ToolRbacEnforcer 既有映射)
--   SSOP_SCHEDULE_PLAN_CREATE               -> ssop_schedule_plan             (写, MEDIUM; quality:read_write)
--   HR_MY_WAGE_QUERY                        -> my_wage_query                  (读, LOW;  hr:self_wage:view, 只看本人)
--   HR_EMPLOYEE_WORK_LOCATION_QUERY         -> employee_work_location_query   (读, LOW;  hr/scheduling/production 读, 只给工作信息)
--   INVENTORY_FINISHED_GOODS_INBOUND_STATUS -> finished_goods_inbound_status  (读, LOW;  成品库存读, 无价格)
-- required_roles / required_permission 不设: 由工具自己的权限码把门 (与 V20261103_135 / V20261115_11 一致)。
-- 事后核对: 每个工具恰好 1 行有效意图绑定。

INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, keywords, description, priority, is_active,
    business_type, created_at, updated_at
)
VALUES
    (gen_random_uuid(), 'CUSTOMER_CREATE', '新建客户', 'DATA_OP', 'customer_create', 'MEDIUM',
     '["新建客户","录入客户资料","客户资料帮我录","录一下客户资料","添加新客户","建个客户档案"]'::jsonb,
     '新建客户资料 (WRITE + Preview): 客户名称必填, 可带联系人/电话/收货地址/类型; 确认卡先说将建哪个客户、同名是否已存在, 确认后才创建。',
     110, true, 'FACTORY', NOW(), NOW()),
    (gen_random_uuid(), 'SSOP_SCHEDULE_PLAN_CREATE', '排清洁计划', 'DATA_OPERATION', 'ssop_schedule_plan', 'MEDIUM',
     '["排清洁计划","明天的清洁计划","清洁计划排一下","安排清洁计划","排卫生计划"]'::jsonb,
     '排某天的 SSOP 清洁计划 (WRITE + Preview): 按启用的清洁标准模板生成那天的待执行清洁任务, 默认明天/早班/全部每日模板, 已排过的跳过; 确认后才生成。',
     110, true, 'FACTORY', NOW(), NOW()),
    (gen_random_uuid(), 'HR_MY_WAGE_QUERY', '我的工资', 'DATA_QUERY', 'my_wage_query', 'LOW',
     '["我的工资","我的工资条","工资条能提前","把我工资算","我这个月工资"]'::jsonb,
     '只读: 当前登录人本人的工资条 (某月报工件数工时、系统试算、工资单按待审/已审/已发分开); 只能看本人, 查别人拒绝; 不是全厂工资成本。',
     90, true, 'FACTORY', NOW(), NOW()),
    (gen_random_uuid(), 'HR_EMPLOYEE_WORK_LOCATION_QUERY', '查员工在哪个车间哪条线', 'DATA_QUERY', 'employee_work_location_query', 'LOW',
     '["在哪条线","哪个车间的","在哪个车间","员工在哪条产线"]'::jsonb,
     '只读: 按姓名查本厂员工的车间/部门、岗位、今天排在哪条产线、手上在做的批次; 只给工作信息不给手机号等个人信息, 同名或只给姓时先列候选按工号选。',
     90, true, 'FACTORY', NOW(), NOW()),
    (gen_random_uuid(), 'INVENTORY_FINISHED_GOODS_INBOUND_STATUS', '成品入库状态', 'DATA_QUERY', 'finished_goods_inbound_status', 'LOW',
     '["成品入库了没","包装好后入库","成品入库状态","成品有没有入库"]'::jsonb,
     '只读: 按批次号或产品名查成品有没有入库、入了多少、可用还是待检, 没入库的给生产状态; 不含价格。',
     90, true, 'FACTORY', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET
    intent_name       = EXCLUDED.intent_name,
    intent_category   = EXCLUDED.intent_category,
    tool_name         = EXCLUDED.tool_name,
    sensitivity_level = EXCLUDED.sensitivity_level,
    keywords          = EXCLUDED.keywords,
    description       = EXCLUDED.description,
    priority          = EXCLUDED.priority,
    is_active         = true,
    business_type     = EXCLUDED.business_type,
    deleted_at        = NULL,
    updated_at        = NOW();

DO $$
DECLARE
    t text;
    bound int;
BEGIN
    FOREACH t IN ARRAY ARRAY['customer_create','ssop_schedule_plan','my_wage_query',
                             'employee_work_location_query','finished_goods_inbound_status'] LOOP
        SELECT count(*) INTO bound FROM ai_intent_configs
         WHERE tool_name = t AND is_active AND deleted_at IS NULL;
        IF bound <> 1 THEN
            RAISE EXCEPTION 'active intents bound to % = % (expected 1)', t, bound;
        END IF;
    END LOOP;
END $$;
