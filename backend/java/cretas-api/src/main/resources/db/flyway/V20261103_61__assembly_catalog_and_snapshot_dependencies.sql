-- Platform Assembly-only table dependencies and immutable versioned catalog.
-- No existing factory business rows or shared module schema definitions are changed.

-- Source db/flyway-factory/V20261102_07__factory_build_plan_snapshots.sql
-- 段③ 复核向导:存下「客户当时说要什么」那一份
--
-- 为什么要这张表(2026-08-17, owner 批准建表):
--   复核的每一屏要把三份并排给客户看 —— **预览说的 / 实际写的 / 现在库里的**。
--   前一份原先**只活在 apply 那一次的响应里**(现数: setPlanned 调用点 1 处、
--   落库路径 0 处), 客户把页面一关就没了, 而「说了什么就做什么, 做了什么就
--   告诉客户」正是目标③。
--
-- DDL 来源: DDL_DB_PORT=45000 bash scripts/testing/generate-entity-ddl.sh \
--             factory_build_plan_snapshots
--           (起一次性库 -> Hibernate ddl-auto=update 真建表 -> pg_dump 导出)
--           【不是手写】—— 手写等于猜实体映射, 猜错只在 ddl-auto=none 的
--           真·独立部署上才炸, 开发与 CI 会因为 update 默默补齐而一切正常。
--   ⚠️ 脚本默认端口 55433 在这台机器上**起不来**: 它落在 Windows 的排除区间
--      55344-55443(netsh interface ipv4 show excludedportrange), 报的是
--      「ports are not available … forbidden by its access permissions」。
--      脚本自己留了 DDL_DB_PORT 覆盖口, 换一个不在排除区间的端口即可。
--
-- 每条都可重复执行: 表/序列/索引 IF NOT EXISTS, 主键查 pg_constraint。
-- 原因: 这张表在开发库上可能已被 ddl-auto 建出来了, 裸 CREATE 会让迁移在老库失败。
--
-- 本文件共 1 张表 / 1 个序列 / 1 个主键 / 1 个索引。

CREATE TABLE IF NOT EXISTS public.factory_build_plan_snapshots (
    id bigint NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    deleted_at timestamp(6) without time zone,
    updated_at timestamp(6) without time zone NOT NULL,
    factory_id character varying(50) NOT NULL,
    planned_count integer NOT NULL,
    planned_json text NOT NULL
);

CREATE SEQUENCE IF NOT EXISTS public.factory_build_plan_snapshots_id_seq
    AS integer START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;

ALTER SEQUENCE public.factory_build_plan_snapshots_id_seq
    OWNED BY public.factory_build_plan_snapshots.id;

ALTER TABLE ONLY public.factory_build_plan_snapshots
    ALTER COLUMN id SET DEFAULT nextval('public.factory_build_plan_snapshots_id_seq'::regclass);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'factory_build_plan_snapshots_pkey'
    ) THEN
        ALTER TABLE ONLY public.factory_build_plan_snapshots
            ADD CONSTRAINT factory_build_plan_snapshots_pkey PRIMARY KEY (id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_fbps_factory
    ON public.factory_build_plan_snapshots USING btree (factory_id);

-- Source db/flyway/V20261102_04__supplier_admission_rule_configs.sql
-- 供应商准入规则配置落库 (2026-08-14).
--
-- 背景: SupplierAdmissionRuleServiceImpl 此前把整份规则配置存在
--   private final Map<String, SupplierRuleConfig> ruleConfigCache = new ConcurrentHashMap<>();
-- 里, updateRuleConfiguration 全程不落库 ⇒ 重启即丢、蓝绿两侧各存各的、多实例互不可见。
--
-- ⛔ 放置目录说明: 本文件必须在 db/flyway (被 spring.flyway.locations 加载)。
--    db/migration 那 256 个 .sql 一条都没执行过, 由 NoMigrationInUnloadedDirTest 钉着。
--
-- 形态: 一个工厂一行 (factory_id 唯一)。两组规则用 jsonb 整存整取 ——
--    与 business_rules.action_config_json 同风格; 字段逐列拆开需要写两遍手工映射,
--    而「一个字段穿三层各自静默丢掉它」正是本仓反复踩过的形状。
--    round-trip 测试直接守住「每个字段都活着回来」。
--
-- ⚠️ 没有对应的既有表可用:
--    - suppliers.admission_status/... (V20260824_04) 是**每个供应商**的审核状态, 不是工厂级门槛
--    - factory_configurations 是带 DRAFT/PUBLISHED 的版本信封, 不是 KV 配置存储
--    - decision_audit_logs 只记结果

CREATE TABLE IF NOT EXISTS supplier_admission_rule_configs (
    id               VARCHAR(64)  NOT NULL,
    factory_id       VARCHAR(50)  NOT NULL,
    config_version   INTEGER      NOT NULL DEFAULT 1,
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    admission_rules  JSONB        NOT NULL DEFAULT '{}'::jsonb,
    acceptance_rules JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_supplier_admission_rule_configs PRIMARY KEY (id)
);

-- 一个工厂只允许一份生效配置 —— 并发两个管理员同时首存时, 第二个撞唯一键而不是静默产生两行
CREATE UNIQUE INDEX IF NOT EXISTS uk_sarc_factory
    ON supplier_admission_rule_configs (factory_id);

COMMENT ON TABLE supplier_admission_rule_configs IS
    '工厂级供应商准入/验收规则配置 (一厂一行)。0 行 = 该厂沿用代码里的默认门槛, 不是缺口。';
COMMENT ON COLUMN supplier_admission_rule_configs.config_version IS
    '配置版本号, 每次 updateRuleConfiguration 自增; 默认配置(未落库)对外报 1, 首次保存后为 2';
COMMENT ON COLUMN supplier_admission_rule_configs.admission_rules IS
    'AdmissionRules: requireBusinessLicense / requireQualityCertificates / minRating / requireCreditLimit / minHistoricalPassRate / minSupplyCount';
COMMENT ON COLUMN supplier_admission_rule_configs.acceptance_rules IS
    'AcceptanceRules: newSupplierLevel / relaxedThreshold / strictThreshold / defaultSamplePercentage / highRiskSamplePercentage / minSampleSize / maxSampleSize';

-- Source db/flyway-factory/V20261102_10__factory_capability_catalog_v1.sql
-- Versioned, immutable cross-module capability/dependency catalog for Factory Blueprint.
-- module_schemas remains the field/workflow/permission authority; references below are links only.

CREATE TABLE public.factory_capability_definitions (
    id bigserial PRIMARY KEY,
    catalog_version character varying(64) NOT NULL,
    capability_id character varying(64) NOT NULL,
    label character varying(100) NOT NULL,
    description text NOT NULL,
    display_order integer NOT NULL,
    derivation_strategy character varying(32) NOT NULL,
    depends_on jsonb NOT NULL DEFAULT '[]'::jsonb,
    match_keywords jsonb NOT NULL DEFAULT '[]'::jsonb,
    configuration_types jsonb NOT NULL DEFAULT '[]'::jsonb,
    module_schema_codes jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT factory_capability_derivation_strategy_check
        CHECK (derivation_strategy IN ('FACTORY', 'PLANNED_CHANGE', 'ROUTE', 'BOM', 'WORKFLOW')),
    CONSTRAINT factory_capability_depends_on_array_check CHECK (jsonb_typeof(depends_on) = 'array'),
    CONSTRAINT factory_capability_match_keywords_array_check CHECK (jsonb_typeof(match_keywords) = 'array'),
    CONSTRAINT factory_capability_configuration_types_array_check CHECK (jsonb_typeof(configuration_types) = 'array'),
    CONSTRAINT factory_capability_module_schema_codes_array_check CHECK (jsonb_typeof(module_schema_codes) = 'array'),
    CONSTRAINT uq_factory_capability_catalog_id UNIQUE (catalog_version, capability_id),
    CONSTRAINT uq_factory_capability_catalog_order UNIQUE (catalog_version, display_order)
);

CREATE INDEX IF NOT EXISTS idx_factory_capability_catalog_version
    ON public.factory_capability_definitions(catalog_version, display_order);

CREATE OR REPLACE FUNCTION public.prevent_factory_capability_definition_mutation()
RETURNS trigger LANGUAGE plpgsql AS $fn$
BEGIN
    RAISE EXCEPTION 'factory capability catalog definitions are immutable: %/%',
        OLD.catalog_version, OLD.capability_id USING ERRCODE = '55000';
    RETURN OLD;
END
$fn$;

DROP TRIGGER IF EXISTS trg_immutable_factory_capability_definition
    ON public.factory_capability_definitions;
CREATE TRIGGER trg_immutable_factory_capability_definition
    BEFORE UPDATE OR DELETE ON public.factory_capability_definitions
    FOR EACH ROW EXECUTE FUNCTION public.prevent_factory_capability_definition_mutation();

INSERT INTO public.factory_capability_definitions
    (catalog_version, capability_id, label, description, display_order,
     derivation_strategy, depends_on, match_keywords, configuration_types, module_schema_codes)
VALUES
    ('factory-capability-catalog/v1', 'factory', '工厂身份', '行业、名称与基础边界', 10,
     'FACTORY', '[]', '[]', '["FACTORY_PROFILE"]', '[]'),
    ('factory-capability-catalog/v1', 'products', '产品档案', '成品编码、名称与单位', 20,
     'PLANNED_CHANGE', '["factory"]', '["产品类型","成品","产品档案"]', '["PRODUCT_TYPE"]', '[]'),
    ('factory-capability-catalog/v1', 'materials', '物料档案', '原辅料、包材与分类', 30,
     'PLANNED_CHANGE', '["factory"]', '["原料","物料","辅料","包材"]', '["RAW_MATERIAL_TYPE"]', '[]'),
    ('factory-capability-catalog/v1', 'processes', '工序标准', '工序、产出与得率边界', 40,
     'PLANNED_CHANGE', '["factory"]', '["工序"]', '["WORK_PROCESS"]', '[]'),
    ('factory-capability-catalog/v1', 'routes', '产品工艺链', '每个产品的工序顺序', 50,
     'ROUTE', '["products","processes"]', '["产品工序链","工艺链"]', '["PRODUCT_PROCESS_ROUTE"]', '[]'),
    ('factory-capability-catalog/v1', 'bom', 'BOM 配方', '物料投入与版本草稿', 60,
     'BOM', '["products","materials","processes"]', '["BOM","配方"]', '["BOM_RECIPE"]', '["bom"]'),
    ('factory-capability-catalog/v1', 'warehouses', '仓储与存放区', '原料、在制品与成品存放', 70,
     'PLANNED_CHANGE', '["factory"]', '["仓库","存放区","库位"]', '["WAREHOUSE","STORAGE_AREA"]', '["warehouse"]'),
    ('factory-capability-catalog/v1', 'packaging', '单位与包装换算', '基本单位、箱袋件换算', 80,
     'PLANNED_CHANGE', '["products","materials"]', '["转换率","包装换算","单位"]', '["UNIT_CONVERSION","PACKAGING_SPEC"]', '[]'),
    ('factory-capability-catalog/v1', 'accounts', '账号与权限', '默认角色与工厂边界', 90,
     'PLANNED_CHANGE', '["factory"]', '["账号","用户","权限","角色"]', '["USER","ROLE_PERMISSION"]', '["hr_employee"]'),
    ('factory-capability-catalog/v1', 'modules', '模块开关', '启用范围与访问门禁', 100,
     'PLANNED_CHANGE', '["factory"]', '["模块开关","模块配置","模块门禁"]', '["FACTORY_MODULE_CONFIG"]', '[]'),
    ('factory-capability-catalog/v1', 'alerts', '告警与指标', '提醒规则、阈值与指标监控', 110,
     'PLANNED_CHANGE', '["modules"]', '["告警","提醒","指标","阈值"]', '["ALERT_RULE","METRIC_THRESHOLD"]', '[]'),
    ('factory-capability-catalog/v1', 'forms', '表单与编码', '采集表单、字段与编码段', 120,
     'PLANNED_CHANGE', '["modules"]', '["表单","编码"]', '["FORM_TEMPLATE","ENCODING_RULE"]', '[]'),
    ('factory-capability-catalog/v1', 'workflow', 'Workflow 发布', '草稿、画布核对与人工发布', 130,
     'WORKFLOW', '["routes","bom"]', '["Workflow","工作流"]', '["WORKFLOW_DEFINITION"]', '[]');

ALTER TABLE public.factory_type_blueprints
    ADD COLUMN IF NOT EXISTS capability_catalog_version character varying(64),
    ADD COLUMN IF NOT EXISTS capability_catalog_digest character varying(64);

ALTER TABLE public.factory_type_blueprints
    DROP CONSTRAINT IF EXISTS factory_blueprint_capability_catalog_pair_check;
ALTER TABLE public.factory_type_blueprints
    ADD CONSTRAINT factory_blueprint_capability_catalog_pair_check
        CHECK ((capability_catalog_version IS NULL AND capability_catalog_digest IS NULL)
            OR (capability_catalog_version IS NOT NULL
                AND capability_catalog_digest IS NOT NULL
                AND capability_catalog_digest ~ '^[0-9a-f]{64}$')) NOT VALID;

-- Source db/flyway-factory/V20261102_13__factory_capability_catalog_v2.sql
-- Food-factory operating-system capability catalog v2.
-- v1 rows remain immutable and readable. The three new metadata arrays pin
-- sector applicability, Stage A authority references and research coverage.

ALTER TABLE public.factory_capability_definitions
    ADD COLUMN sector_profiles jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN authority_refs jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN requirement_codes jsonb NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE public.factory_capability_definitions
    ADD CONSTRAINT factory_capability_sector_profiles_array_check
        CHECK (jsonb_typeof(sector_profiles) = 'array'),
    ADD CONSTRAINT factory_capability_authority_refs_array_check
        CHECK (jsonb_typeof(authority_refs) = 'array'),
    ADD CONSTRAINT factory_capability_requirement_codes_array_check
        CHECK (jsonb_typeof(requirement_codes) = 'array');

COMMENT ON COLUMN public.factory_capability_definitions.sector_profiles IS
    'Validated applicability: ALL_FOOD plus the four independently audited archetypes.';
COMMENT ON COLUMN public.factory_capability_definitions.authority_refs IS
    'Version-pinned identifiers from the 2026-08-27 authoritative research ledger.';
COMMENT ON COLUMN public.factory_capability_definitions.requirement_codes IS
    'Stable mapping to the 23-row food-safety capability matrix.';

INSERT INTO public.factory_capability_definitions
    (catalog_version, capability_id, label, description, display_order,
     derivation_strategy, depends_on, match_keywords, configuration_types, module_schema_codes,
     sector_profiles, authority_refs, requirement_codes)
VALUES
    ('factory-capability-catalog/v2', 'factory', '工厂身份与适用范围', '许可范围、工厂边界与行业标准基线', 10,
     'FACTORY', '[]', '[]', '["FACTORY_PROFILE","SECTOR_STANDARD_PROFILE"]', '[]',
     '["ALL_FOOD"]', '["S01_GB14881_2013","S02_PRC_FOOD_SAFETY_LAW_RESEARCHED_2026_08_27","S03_PRC_IMPLEMENTATION_REGULATION_RESEARCHED_2026_08_27"]', '["factory-scope-sector"]'),
    ('factory-capability-catalog/v2', 'products', '产品、SKU 与标签', '产品版本、净含量、保质期、储存条件与标签复核', 20,
     'PLANNED_CHANGE', '["factory"]', '["产品类型","成品","产品档案","标签","保质期"]', '["PRODUCT_TYPE","PRODUCT_LABEL_SPEC"]', '[]',
     '["ALL_FOOD"]', '["S01_GB14881_2013","S02_PRC_FOOD_SAFETY_LAW_RESEARCHED_2026_08_27"]', '["product-sku-packaging"]'),
    ('factory-capability-catalog/v2', 'materials', '原辅料、添加剂与包材', '规格、批次、COA、添加剂限量与食品接触包材状态', 30,
     'PLANNED_CHANGE', '["factory"]', '["原料","物料","辅料","包材","添加剂"]', '["RAW_MATERIAL_TYPE","MATERIAL_SPECIFICATION"]', '[]',
     '["ALL_FOOD"]', '["S01_GB14881_2013","S02_PRC_FOOD_SAFETY_LAW_RESEARCHED_2026_08_27"]', '["material-specifications"]'),
    ('factory-capability-catalog/v2', 'supplier-receiving', '供应商准入与来料验收', '批准供应商、风险等级、证件、检验与不合格处置', 40,
     'PLANNED_CHANGE', '["materials"]', '["供应商","来料","进货","收货检验"]', '["APPROVED_SUPPLIER","INCOMING_INSPECTION"]', '[]',
     '["ALL_FOOD"]', '["S01_GB14881_2013","S02_PRC_FOOD_SAFETY_LAW_RESEARCHED_2026_08_27"]', '["supplier-incoming"]'),
    ('factory-capability-catalog/v2', 'processes', '工序标准', '工序身份、产出和基础加工边界', 50,
     'PLANNED_CHANGE', '["factory"]', '["工序","加工步骤"]', '["WORK_PROCESS"]', '[]',
     '["ALL_FOOD"]', '["S01_GB14881_2013","S06_CXC1_1969_REV2022"]', '["process-flow-segregation"]'),
    ('factory-capability-catalog/v2', 'routes', '无损工艺流程图', '任意长度、分支、合流与生熟隔离的产品工艺图', 60,
     'ROUTE', '["products","processes"]', '["产品工序链","工艺链","分支","合流"]', '["PRODUCT_PROCESS_ROUTE","PROCESS_FLOW_GRAPH"]', '[]',
     '["ALL_FOOD"]', '["S01_GB14881_2013","S06_CXC1_1969_REV2022"]', '["process-flow-segregation"]'),
    ('factory-capability-catalog/v2', 'bom', '配方、BOM 与返工谱系', '有效版本、替代约束、客供料和返工来源比例与期限', 70,
     'BOM', '["products","materials","processes"]', '["BOM","配方","返工","替代料"]', '["BOM_RECIPE","REWORK_GENEALOGY"]', '["bom"]',
     '["ALL_FOOD"]', '["S01_GB14881_2013","S06_CXC1_1969_REV2022"]', '["formula-bom-rework"]'),
    ('factory-capability-catalog/v2', 'hazard-analysis', '危害分析与控制措施', '危害原因、严重性、可能性、控制措施与 CCP/OPRP 判定依据', 80,
     'PLANNED_CHANGE', '["routes","bom"]', '["危害分析","危害","CCP判定","预防控制"]', '["HAZARD_ANALYSIS","CONTROL_MEASURE"]', '[]',
     '["ALL_FOOD"]', '["S06_CXC1_1969_REV2022","S07_US_21CFR117_CROSSCHECK"]', '["hazard-analysis"]'),
    ('factory-capability-catalog/v2', 'critical-controls', '关键限值、监控与验证', '带单位与方向的限值、时间、方法、频率、责任人、纠偏与再验证', 90,
     'PLANNED_CHANGE', '["hazard-analysis"]', '["关键控制点","临界限值","监控频率","纠偏","验证"]', '["CRITICAL_CONTROL","MONITORING_PLAN","VERIFICATION_PLAN"]', '[]',
     '["ALL_FOOD"]', '["S01_GB14881_2013","S06_CXC1_1969_REV2022"]', '["critical-limits-monitoring"]'),
    ('factory-capability-catalog/v2', 'allergen-label', '过敏原与标签控制', '过敏原矩阵、排产、换线清场、验证与标签版本核对', 100,
     'PLANNED_CHANGE', '["products","materials","routes"]', '["过敏原","标签复核","换线清场"]', '["ALLERGEN_MATRIX","LABEL_CONTROL","LINE_CLEARANCE"]', '[]',
     '["ALL_FOOD","BAKERY","FROZEN_PREPARED_CENTRAL_KITCHEN"]', '["S02_PRC_FOOD_SAFETY_LAW_RESEARCHED_2026_08_27","S06_CXC1_1969_REV2022","S04_GB8957_2016","S04_GB31646_2018"]', '["allergen-label-control"]'),
    ('factory-capability-catalog/v2', 'sanitation-cip', '清洗消毒与 CIP', 'SSOP/CIP 版本、化学品、浓度、温度、时间、流量、清场和放行', 110,
     'PLANNED_CHANGE', '["routes"]', '["清洗","消毒","CIP","SSOP","虫害","废弃物"]', '["SANITATION_PROGRAM","CIP_RECIPE","PRE_OPERATION_RELEASE"]', '[]',
     '["ALL_FOOD","BEVERAGE_SAUCE"]', '["S01_GB14881_2013","S06_CXC1_1969_REV2022","S04_GB12695_2016"]', '["sanitation-cip"]'),
    ('factory-capability-catalog/v2', 'environmental-monitoring', '环境与病原监测', '分区采样点、对象、方法、频率、趋势、升级和受影响产品评估', 120,
     'PLANNED_CHANGE', '["sanitation-cip"]', '["环境监测","病原","涂抹","采样点"]', '["ENVIRONMENTAL_MONITORING_PLAN","PATHOGEN_RESULT"]', '[]',
     '["ALL_FOOD","CHILLED_RTE_MEAT","BEVERAGE_SAUCE","FROZEN_PREPARED_CENTRAL_KITCHEN"]', '["S01_GB14881_2013","S06_CXC1_1969_REV2022","S07_US_21CFR117_CROSSCHECK"]', '["environmental-pathogen-monitoring"]'),
    ('factory-capability-catalog/v2', 'hygienic-zones', '卫生分区与定向流', '卫生等级、屏障以及人流、物流、工器具和气流相容性', 130,
     'PLANNED_CHANGE', '["factory"]', '["卫生分区","生熟分开","人流","物流","气流"]', '["HYGIENIC_ZONE_GRAPH","DIRECTIONAL_FLOW"]', '[]',
     '["ALL_FOOD","CHILLED_RTE_MEAT","FROZEN_PREPARED_CENTRAL_KITCHEN"]', '["S01_GB14881_2013","S06_CXC1_1969_REV2022","S04_GB31646_2018","S05_GB19295_2011"]', '["hygienic-zones-flows"]'),
    ('factory-capability-catalog/v2', 'warehouses', '仓储、储存与冷链', '分区温湿度、报警、偏离、隔离和运输交接', 140,
     'PLANNED_CHANGE', '["factory"]', '["仓库","存放区","库位","冷藏","冷冻","冷链"]', '["WAREHOUSE","STORAGE_AREA","COLD_CHAIN_CONTROL"]', '["warehouse"]',
     '["ALL_FOOD"]', '["S01_GB14881_2013","S03_PRC_IMPLEMENTATION_REGULATION_RESEARCHED_2026_08_27"]', '["warehouse-cold-chain"]'),
    ('factory-capability-catalog/v2', 'packaging', '单位、包装与净含量', '基本单位、箱袋件换算、包装层级与净含量', 150,
     'PLANNED_CHANGE', '["products","materials"]', '["转换率","包装换算","单位","净含量"]', '["UNIT_CONVERSION","PACKAGING_SPEC"]', '[]',
     '["ALL_FOOD"]', '["S01_GB14881_2013","S02_PRC_FOOD_SAFETY_LAW_RESEARCHED_2026_08_27"]', '["product-sku-packaging"]'),
    ('factory-capability-catalog/v2', 'equipment-calibration', '设备、维护与校准', '设备主数据、食品接触状态、维护清洁校准计划和超期门禁', 160,
     'PLANNED_CHANGE', '["processes"]', '["设备","维护","保养","校准","检定"]', '["EQUIPMENT_MASTER","MAINTENANCE_PLAN","CALIBRATION_PLAN"]', '["equipment"]',
     '["ALL_FOOD"]', '["S01_GB14881_2013","S06_CXC1_1969_REV2022"]', '["equipment-maintenance-calibration"]'),
    ('factory-capability-catalog/v2', 'quality-release', '检验、抽样与放行', '来料、过程、成品规格、抽样、结果、OOS、留样与 QA 放行', 170,
     'PLANNED_CHANGE', '["critical-controls","materials"]', '["质检","抽样","留样","放行","OOS","COA"]', '["QUALITY_SPECIFICATION","SAMPLING_PLAN","PRODUCT_RELEASE"]', '["quality_inspection"]',
     '["ALL_FOOD"]', '["S01_GB14881_2013","S02_PRC_FOOD_SAFETY_LAW_RESEARCHED_2026_08_27"]', '["quality-sampling-release"]'),
    ('factory-capability-catalog/v2', 'deviation-capa', '偏差、隔离、处置与 CAPA', '批次关联的异常、电子隔离、授权处置、根因和有效性复核', 180,
     'PLANNED_CHANGE', '["critical-controls","quality-release"]', '["偏差","不合格","隔离","处置","CAPA","纠正措施"]', '["DEVIATION_CASE","HOLD_DISPOSITION","CAPA"]', '[]',
     '["ALL_FOOD"]', '["S02_PRC_FOOD_SAFETY_LAW_RESEARCHED_2026_08_27","S03_PRC_IMPLEMENTATION_REGULATION_RESEARCHED_2026_08_27","S06_CXC1_1969_REV2022"]', '["deviation-capa"]'),
    ('factory-capability-catalog/v2', 'trace-recall', '追溯、质量平衡与召回', '双向批次谱系、供应商和客户链接、模拟召回与执行回执', 190,
     'PLANNED_CHANGE', '["routes","bom","warehouses","quality-release"]', '["追溯","召回","模拟召回","质量平衡"]', '["LOT_GENEALOGY","RECALL_PLAN","MOCK_RECALL"]', '[]',
     '["ALL_FOOD"]', '["S01_GB14881_2013","S02_PRC_FOOD_SAFETY_LAW_RESEARCHED_2026_08_27","S03_PRC_IMPLEMENTATION_REGULATION_RESEARCHED_2026_08_27","S06_CXC1_1969_REV2022"]', '["traceability-recall"]'),
    ('factory-capability-catalog/v2', 'accounts', '角色、培训与电子审批', '岗位资格、培训有效期、健康和区域授权及理由化电子签署', 200,
     'PLANNED_CHANGE', '["factory"]', '["账号","用户","权限","角色","培训","健康","电子签名"]', '["USER","ROLE_PERMISSION","COMPETENCY_RECORD","ELECTRONIC_APPROVAL"]', '["hr_employee"]',
     '["ALL_FOOD"]', '["S01_GB14881_2013","S03_PRC_IMPLEMENTATION_REGULATION_RESEARCHED_2026_08_27"]', '["roles-training-health-approval"]'),
    ('factory-capability-catalog/v2', 'modules', '模块与访问门禁', '启用范围、访问边界和食品安全记录承载模块', 210,
     'PLANNED_CHANGE', '["factory"]', '["模块开关","模块配置","模块门禁"]', '["FACTORY_MODULE_CONFIG"]', '[]',
     '["ALL_FOOD"]', '["S01_GB14881_2013"]', '["records-retention-audit"]'),
    ('factory-capability-catalog/v2', 'forms', '记录、留存与审计轨迹', '记录模板、留存期、不可变修订、责任人、时间、原因与导出', 220,
     'PLANNED_CHANGE', '["modules"]', '["表单","编码","记录","留存","审计轨迹"]', '["FORM_TEMPLATE","ENCODING_RULE","RECORD_RETENTION","AUDIT_TRAIL"]', '[]',
     '["ALL_FOOD"]', '["S01_GB14881_2013","S02_PRC_FOOD_SAFETY_LAW_RESEARCHED_2026_08_27","S06_CXC1_1969_REV2022"]', '["records-retention-audit"]'),
    ('factory-capability-catalog/v2', 'alerts', '告警、升级与响应', '责任人、升级链、SLA、抑制、确认和选择来源', 230,
     'PLANNED_CHANGE', '["modules"]', '["告警","提醒","指标","阈值","升级"]', '["ALERT_RULE","METRIC_THRESHOLD","ESCALATION_POLICY"]', '[]',
     '["ALL_FOOD"]', '["S01_GB14881_2013","S06_CXC1_1969_REV2022"]', '["alerts-escalation"]'),
    ('factory-capability-catalog/v2', 'contract-material', '委托加工与客供料', '委托双方责任、客户批次所有权、验收、隔离、使用和核销', 240,
     'PLANNED_CHANGE', '["materials","supplier-receiving"]', '["委托加工","客供料","客户物料","受托"]', '["CONTRACT_MANUFACTURE","CUSTOMER_SUPPLIED_MATERIAL"]', '[]',
     '["ALL_FOOD"]', '["S03_PRC_IMPLEMENTATION_REGULATION_RESEARCHED_2026_08_27"]', '["contract-customer-material"]'),
    ('factory-capability-catalog/v2', 'emergency-defense', '召回、应急与食品防护', '召回、停电停水、冷链异常和食品防护的计划、联系人、演练与改进', 250,
     'PLANNED_CHANGE', '["trace-recall","alerts"]', '["应急","停电","停水","食品防护","召回演练"]', '["EMERGENCY_PLAN","UTILITY_FAILURE_PLAN","FOOD_DEFENSE_PLAN"]', '[]',
     '["ALL_FOOD"]', '["S01_GB14881_2013","S02_PRC_FOOD_SAFETY_LAW_RESEARCHED_2026_08_27","S06_CXC1_1969_REV2022"]', '["emergency-defense-utility"]'),
    ('factory-capability-catalog/v2', 'workflow', 'Workflow 人工发布', '流程图、BOM 与食品安全控制核对后由人单独发布', 260,
     'WORKFLOW', '["routes","bom","critical-controls"]', '["Workflow","工作流"]', '["WORKFLOW_DEFINITION"]', '[]',
     '["ALL_FOOD"]', '["S01_GB14881_2013","S06_CXC1_1969_REV2022"]', '["workflow-human-publication"]');

