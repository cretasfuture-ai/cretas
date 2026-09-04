-- ============================================================
-- 修复: role_definitions 表在 cretas_prod_db 上从未存在过
-- ============================================================
-- 背景 (2026-09-04, prod WARN 排查):
--   com.cretas.aims.security.DataScopeResolver 每次解析角色的 data scope 都跑
--     SELECT data_scope FROM role_definitions WHERE role_code = ? LIMIT 1
--   在 cretas_prod_db 上实测 (只读 psql, 2026-09-04):
--     ERROR:  relation "role_definitions" does not exist
--   根因: 这张表原本只在 MySQL 时代的 legacy migration 里定义
--   (db/migration/V2025_12_27__add_role_hierarchy_fields.sql, 从未在 db/flyway/
--   下有对应版本)。唯一试图在 Postgres 上补建它的
--   V20260416_00__bootstrap_completeness.sql 用 CREATE TABLE IF NOT EXISTS,
--   但 cretas_prod_db 的 flyway_schema_history 里 baseline 记的是
--   20260416.99 —— 比 V20260416_00 (20260416.00) 还新, 所以那个文件在这台库上
--   从未真正执行过 (baseline 之下的版本一律跳过, 按"已手工应用"处理, 但实际没有)。
--   后续 V20260519_08__role_data_scope.sql 只是给已存在的表加 data_scope 列的
--   防御性 DO 块 (IF EXISTS ... ELSE RAISE NOTICE), 表不存在时它确认过 success=true
--   但什么都没做 —— 于是这条 SELECT 从 Sprint 5 (2026-05-19) 上线起就没成功过一次,
--   DataScopeResolver 一直靠 catch(Exception) 之后的硬编码角色默认值兜底。
--
-- 这个 migration 做两件事:
--   1. 真正建表 (CREATE TABLE IF NOT EXISTS, 幂等, 不影响已经手工建过表的环境)
--   2. 种子数据: 与 DataScopeResolver.resolveScopeForRole() 硬编码 fallback 保持
--      一致 —— 一线员工 (operator/warehouse_worker/quality_inspector/yield_operator)
--      → SELF, 其余角色维持向后兼容的 ALL。
--
--   ⚠️ 同一个 commit 里 DataScopeResolver 的 Java switch 语句也补上了 yield_operator
--   → SELF (此前遗漏, 是本轮发现的真实安全缺陷: LIUSHANMEN 9 个 yield_operator
--   账号 —— 该厂最大的角色群体 —— 之前落到 switch 的 default 分支拿到 ALL,
--   而不是 operator/warehouse_worker/quality_inspector 那样的 SELF)。
--   这里的种子值与 Java 硬默认值保持一致, 使得"表不存在"和"表存在但配置正常"
--   两条路径解析出的 scope 相同 —— 表修好之后代码里的 fallback 分支就成了纯粹的
--   防御层, 不再是唯一生效的路径。
-- ============================================================

CREATE TABLE IF NOT EXISTS role_definitions (
    id              SERIAL       PRIMARY KEY,
    role_code       VARCHAR(50)  NOT NULL,
    display_name    VARCHAR(50)  NOT NULL,
    description     VARCHAR(200),
    level           INTEGER      NOT NULL DEFAULT 99,
    department      VARCHAR(50),
    is_deprecated   BOOLEAN      NOT NULL DEFAULT FALSE,
    data_scope      VARCHAR(32)  NOT NULL DEFAULT 'ALL',
    created_at      TIMESTAMP    DEFAULT NOW(),
    updated_at      TIMESTAMP    DEFAULT NOW(),
    CONSTRAINT uk_role_definitions_role_code UNIQUE (role_code)
);

-- 防御性补列: 万一某个环境已经有这张表 (例如手工建过) 但缺 data_scope, 与
-- V20260519_08 的意图保持一致, 这里再补一次 (幂等, IF NOT EXISTS)。
ALTER TABLE role_definitions
    ADD COLUMN IF NOT EXISTS data_scope VARCHAR(32) NOT NULL DEFAULT 'ALL';

COMMENT ON COLUMN role_definitions.data_scope IS
    '数据权限范围 (Sprint 5 Track G): ALL / DEPT_AND_BELOW / SELF_AND_BELOW / SELF / CUSTOM';

CREATE INDEX IF NOT EXISTS idx_role_definitions_data_scope
    ON role_definitions (data_scope);

-- 种子数据 — 与 com.cretas.aims.entity.enums.FactoryUserRole 的 level/department
-- 以及 DataScopeResolver.resolveScopeForRole() 的硬默认值对齐。
INSERT INTO role_definitions (role_code, display_name, description, level, department, is_deprecated, data_scope) VALUES
    ('platform_admin',        '平台管理员',   '跨工厂平台级权限',           -1, 'platform',   FALSE, 'ALL'),
    ('factory_super_admin',   '工厂总监',     '拥有工厂所有权限',            0, 'all',        FALSE, 'ALL'),
    ('hr_admin',               'HR管理员',     '人事管理、考勤、薪资',       10, 'hr',         FALSE, 'ALL'),
    ('procurement_manager',    '采购主管',     '供应商、采购、成本',         10, 'procurement',FALSE, 'ALL'),
    ('sales_manager',          '销售主管',     '客户、订单、出货',           10, 'sales',      FALSE, 'ALL'),
    ('operations_coordinator', '运营协调员',   '跨部门来源单、到料预告与履约协调', 10, 'operations', FALSE, 'ALL'),
    ('dispatcher',             '调度',         '生产调度、数据分析、趋势监控', 10, 'dispatch',  FALSE, 'ALL'),
    ('production_manager',     '生产经理',     '生产统筹、排产协调、报工审核', 10, 'production', FALSE, 'ALL'),
    ('production_clerk',       '生产文员',     '审核报工数据、录入生产数据、维护产品基础数据', 15, 'production', FALSE, 'ALL'),
    ('warehouse_manager',      '仓储主管',     '库存、出入库、盘点',         10, 'warehouse',  FALSE, 'ALL'),
    ('equipment_admin',        '设备管理员',   '设备维护、保养、告警',       10, 'equipment',  FALSE, 'ALL'),
    ('quality_manager',        '质量经理',     '质量体系、质检审核',         10, 'quality',    FALSE, 'ALL'),
    ('quality_controller',     '品控',         '质量标准制定、特批、处置审核', 15, 'quality',   FALSE, 'ALL'),
    ('finance_manager',        '财务主管',     '成本核算、费用、报表',       10, 'finance',    FALSE, 'ALL'),
    ('cashier',                '出纳',         '负责付款操作、银行对账',     15, 'finance',    FALSE, 'ALL'),
    ('restaurant_manager',     '餐饮管理',     '菜单、配方、餐厅运营',       10, 'restaurant', FALSE, 'ALL'),
    ('restaurant_owner',       '餐饮老板',     '全餐饮运营、价格异常审批、月对账', 5, 'restaurant', FALSE, 'ALL'),
    ('restaurant_chef',        '厨师长',       '报货、领料、验收入库',       15, 'restaurant', TRUE,  'ALL'),
    ('restaurant_purchaser',   '餐饮采购',     '请购、采购确认、采购审批',   15, 'restaurant', FALSE, 'ALL'),
    ('workshop_supervisor',    '车间主任',     '车间日常、人员调度',         20, 'workshop',   FALSE, 'ALL'),
    ('team_leader',            '大组长',       '多组带班、任务分派',         25, 'workshop',   FALSE, 'ALL'),
    ('group_leader',           '小组长',       '本组日常、现场执行',         28, 'workshop',   FALSE, 'ALL'),
    ('quality_inspector',      '质检员',       '执行质检、提交报告',         30, 'quality',    FALSE, 'SELF'),
    ('operator',                '操作员',       '生产执行、打卡记录',         30, 'production', FALSE, 'SELF'),
    ('yield_operator',         '报工操作员',   '报工写入: 产量/工时/工序报告', 30, 'production', FALSE, 'SELF'),
    ('warehouse_worker',       '仓库员',       '出入库操作、盘点',           30, 'warehouse',  FALSE, 'SELF'),
    ('permission_admin',       '权限管理员',   '管理用户权限和角色',         10, 'system',     TRUE,  'ALL'),
    ('department_admin',       '部门管理员',   '管理部门相关业务',           15, 'department', TRUE,  'ALL'),
    ('viewer',                 '查看者',       '只读访问',                   50, 'none',       FALSE, 'ALL'),
    ('unactivated',            '未激活',       '账户未激活',                 99, 'none',       FALSE, 'ALL')
ON CONFLICT (role_code) DO UPDATE SET
    display_name  = EXCLUDED.display_name,
    description   = EXCLUDED.description,
    level         = EXCLUDED.level,
    department    = EXCLUDED.department,
    is_deprecated = EXCLUDED.is_deprecated,
    data_scope    = EXCLUDED.data_scope,
    updated_at    = NOW();
