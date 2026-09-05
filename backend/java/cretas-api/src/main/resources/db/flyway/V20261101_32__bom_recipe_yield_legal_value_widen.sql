-- 标准出成率: 100.00 不再是哨兵, > 100 合法, 上限放宽到 300
-- 任务 RECON-LEGAL-VALUE-SENTINEL-20260906 (2026-09-06)
--
-- ============================================================================
-- 背景 (一句话): bom_recipes.overall_yield_rate 的 100.00 曾经被后端当成
-- 「未配置」哨兵, 而它同时是一个完全合法的取值(零损耗) —— 任何能到达这个值的
-- 过程都会静默关掉依赖它的机制。后端那道哨兵判据已在同一个 PR 里拆掉:
--   ProductionPlanServiceImpl#resolveEffectiveProductYieldRate 不再
--   (a) 按 BomRecipeItem.yieldRate != 100 数「主料」, 也不再
--   (b) 把 overall_yield_rate == 100.00 读成「没配」。
-- 口径统一为: NULL = 没配; 100.00 = 零损耗(合法); > 100 = 保水/腌制(合法)。
--
-- ============================================================================
-- (a) 放宽 chk_br_yield: 上限 100 → 300, 并把 NULL 写显式
-- ----------------------------------------------------------------------------
-- ⚠️ prod 上这条约束现在的定义 (2026-09-06 只读 SSH 原样抄出来, 不是凭记忆重写):
--   ssh root@47.100.235.168 "sudo -u postgres psql -d cretas_prod_db -At -c \
--     \"SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='chk_br_yield'\""
--   → CHECK (((overall_yield_rate > (0)::numeric) AND (overall_yield_rate <= (100)::numeric)))
--   convalidated = t
-- 它由 V20260516_03__bom_redesign.sql:55 建立(与 prod 一致, 不是带外手工加的)。
--
-- 🔴 为什么必须动它: BomYieldApplyRequest 早就<b>故意</b>去掉了 ≤100 的上限
-- (保水/腌制工序出成率天然超过 100, 六膳门猪舌实测 105–126%), 但 DB 这一侧
-- 从来没跟着放。⇒ 用户填 110% 会在 PG 上 500 + 事务回滚, 而<b>所有单测全绿</b>:
-- H2 测试 profile (src/test/resources/application-test.properties) 是
-- `spring.flyway.enabled=false` + `spring.jpa.hibernate.ddl-auto=create`,
-- 表由 Hibernate 按 entity 建, entity 上没有这条 CHECK ⇒ H2 对它的存在与否完全无感。
-- 与 V20261101_29 头注释记的 ck_so_status 是同一类缺陷(#581 同形)。
--
-- 兼容性 (硬约束: 保住每一个现存的合法值):
--   旧约束接受的集合 = {NULL} ∪ (0, 100]
--   新约束接受的集合 = {NULL} ∪ (0, 300]   ⇒ 严格超集, 存量一行都不会被拒。
--   prod 实测存量: 81 行 bom_recipes, 其中 80 行 NULL + 1 行 100.00, 0 行 > 100。
-- NULL 在旧写法下本来也通过(NULL > 0 求值为 NULL, CHECK 视 NULL 为满足), 这里
-- 把它写成显式的 `IS NULL OR (...)` 只是让「NULL = 没配是合法状态」在约束里看得见,
-- ⛔ 不是行为变更。
--
-- DROP + ADD 是 PostgreSQL 改 CHECK 取值范围唯一的路。
-- DROP ... IF EXISTS 对「这个库里压根没有这条约束」是安全的 no-op。
-- 本迁移只动 public.bom_recipes(不限定 schema, 走 search_path —— 与本目录其它迁移
-- 一致; prod 上 `bom_recipes`::regclass 解析到 public, 另有一个 f006_clear_71
-- 备份 schema 不在 search_path 里, 不受影响)。
ALTER TABLE bom_recipes DROP CONSTRAINT IF EXISTS chk_br_yield;

ALTER TABLE bom_recipes ADD CONSTRAINT chk_br_yield CHECK (
    overall_yield_rate IS NULL
    OR (overall_yield_rate > 0 AND overall_yield_rate <= 300)
);

-- ⛔ bom_recipe_items.chk_bri_yield 故意<b>不动</b>: 行级出成率已废弃(写入侧硬写
-- 100.00), 它不是这次的载体。prod 实测 144/144 行都是 100.00, 放宽它没有任何收益,
-- 只会多一份可漂的规则。它现在的定义是
-- CHECK (yield_rate > 0 AND yield_rate <= 100), 保持原样。

-- ============================================================================
-- (b) 把「SYSTEM 自动 apply 出来的那个 100.00」清回 NULL
-- ----------------------------------------------------------------------------
-- 为什么要清: 那个 100.00 不是任何人的意思表示。它是 PR #594 删掉的
-- autoApplyIfGuarded 循环写进去的 —— 轨迹 (空)→93.75→…→96.88→100.00→之后 6 次
-- 全停在 100.00 (bom_yield_suggestions id 108..119, 全部 applied_by='SYSTEM')。
-- 拆掉哨兵之后, 这个残留值会从「静默关掉折算」变成「按 100% 零损耗折算」——
-- 一个看起来很确定的假答案。诚实的状态是 NULL(没配), 由人去「标准出成率」页面填。
--
-- ⚠️ 谓词按<b>证据</b>写, ⛔ 不硬编码那一行的 id:
--   ① 当前值恰好是 100.00
--   ② 同租户同 SKU 上存在 applied_by='SYSTEM' 且 suggested_yield_rate=100.00 的
--      APPLIED 建议 (未软删) —— 这就是「这个值是机器写的」的证据
--   ③ 这条配方上没有任何人工改动留痕 (bom_change_logs.changed_by IS NOT NULL) ——
--      防止本迁移部署之前有人手工把它设成 100.00 而被我们覆盖掉
--      (人工写入路径 BomYieldEstimateServiceImpl#recalculateApply 必写 BomChangeLog)
--
-- 幂等: 跑完之后该行是 NULL, 条件 ① 不再成立 ⇒ 再跑匹配 0 行。
--
-- 2026-09-06 prod 只读预演 (SELECT 版同谓词):
--   命中 1 行 → 259cb14f-3281-4d54-80c3-f8ce7b144308 / F006 /
--               SOP-20260817-01-黄油鸡-成品800g / 100.00
--   阴性对照(值=100.00 但没有 SYSTEM 证据的行) → 0 行 ⇒ 谓词没有误伤面
--   旁证: 该行 updated_at = 2026-08-24 21:21:53.996108, 与 suggestion id=113
--         (suggested 100.00, applied_by=SYSTEM) 的 applied_at .995826 同一瞬间;
--         该配方的 bom_change_logs 是 0 行 ⇒ 从来没有人碰过它。
UPDATE bom_recipes r
SET overall_yield_rate = NULL,
    updated_at = now()
WHERE r.overall_yield_rate = 100.00
  AND EXISTS (
        SELECT 1
        FROM bom_yield_suggestions s
        WHERE s.factory_id = r.factory_id
          AND s.product_type_id = r.product_type_id
          AND s.status = 'APPLIED'
          AND s.applied_by = 'SYSTEM'
          AND s.suggested_yield_rate = 100.00
          AND s.deleted_at IS NULL
      )
  AND NOT EXISTS (
        SELECT 1
        FROM bom_change_logs cl
        WHERE cl.bom_recipe_id = r.id
          AND cl.changed_by IS NOT NULL
      );
