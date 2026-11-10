-- V20261110_01__backfill_batch_planned_unit_from_plan.sql
--
-- D-W-01 follow-up to #1553: 给「计划量是从计划拷来的」存量批次补上 planned_unit, 并把其中
-- 跨单位算出来的假效率清成 NULL。
--
-- ============================================================================
-- 背景
-- ----------------------------------------------------------------------------
-- #1553 起, 每个新建批次都在 production_batches.planned_unit 里记下 planned_quantity 所用的单位,
-- ProductionBatch.calculateMetrics() 只有在 planned_unit 与 unit 等价时才算 efficiency = actual/planned,
-- 否则留 NULL。本迁移之前建的批次 planned_unit 全是 NULL。
--
-- 其中「planned_quantity 是从计划原样拷来的」那一类, 真实单位就是计划的 planned_unit; 而它们的
-- efficiency 可能是旧代码跨单位算出来的 (kg 的产出 / 盒的计划量), 没有意义。
--
-- ============================================================================
-- 判据 (⛔ 不硬编码任何批次 id)
-- ----------------------------------------------------------------------------
-- 只动同时满足全部条件的批次:
--   (1) planned_unit IS NULL                       -- 没记过单位的存量行
--   (2) planned_quantity IS NOT NULL
--       AND planned_quantity = 所挂计划的 planned_quantity
--       —— 「数相等」是它是从计划拷来的唯一可证据: 数不等就说明批次的计划量是别的口径,
--          ⛔ 不猜它的单位。
--   (3) 计划存在 (JOIN) 且 planned_unit 非空非空白, 且去空白后 <= 20 个字符
--       —— 批次列是 VARCHAR(20), 计划列是 VARCHAR(32); 超长不截断 (截断 = 编造单位),
--          而是跳过, 免得一条超长值让整个迁移 22001 失败、后端起不来。
--   (4) 批次未软删 (deleted_at IS NULL) —— 与实体 @Where 同口径。
--       计划不按 deleted_at 过滤: 计划被软删不改变「这个批次的计划量当年是按它的单位拷的」这一事实,
--       且 ProductionBatch.sourcePlanUnit 的 @Formula 读计划时也不过滤。
-- 不命中的 (各自保持原样, 故意不改):
--   * 没挂计划的批次: 计划量就是按批次自己的 unit 填的, 实体对 planned_unit=NULL 且无计划已有处理。
--   * production_plan_id 指向已不存在的计划: 证明不出单位。
--   * 计划量与批次计划量不等的批次: 见 (2)。
--
-- 同一条 UPDATE 里做两件事 (所以「清效率的行」恰好等于「写了 planned_unit 的行」, 不会漂):
--   a. planned_unit = btrim(计划.planned_unit)
--   b. 若 btrim(批次.unit) 与 btrim(新 planned_unit) 字面不同, efficiency = NULL; 否则 efficiency 原样。
--
-- ⚠️ b 是「字面比较」, 不做单位换算: 等价但写法不同的 (千克 vs kg) 也会被清成 NULL。这是可以接受的 ——
--    它只会少报不会多报 (NULL = 暂不知道), 实体在下一次 save 时按单位契约的等价关系重算
--    (calculateMetrics 的 efficiencyComparable), 等价的会自己长回来; 反过来「字面不同就保留旧值」
--    才会把跨单位的假值留在库里。
--
-- ⛔ 不动其它任何列, 尤其不动 updated_at: 这是纠正元数据的回填, 不是业务修改, 动 updated_at 会让
--    这些批次在「最近更新」排序里全部冒出来。
--
-- ============================================================================
-- 幂等 / 零命中 / 空库
-- ----------------------------------------------------------------------------
--   * 幂等: WHERE 带 planned_unit IS NULL, 第二次跑命中 0 行。
--   * 零命中: 没有匹配行时 UPDATE 0 行, 不报错。
--   * 空库 / 全新 CI 库: Flyway 先于 ddl-auto 跑, 此时两张表可能还不存在 (同
--     V20260906_04 的 to_regclass 守卫理由)。表或列不齐就整块跳过, Hibernate 随后按 entity 建表建列。
--     to_regclass 不加 schema 前缀, 跟随 search_path (prod 即 public)。
--
-- ============================================================================
-- prod 只读实测 (2026-11-10)
-- ----------------------------------------------------------------------------
--   production_batches 共 1765 行, planned_unit IS NULL 的 1764 行。
--   其中命中本迁移 (挂着存在的计划且 planned_quantity = 计划.planned_quantity) 的 9 行, 全在 F006:
--     4 行 批次 kg  / 计划 盒  (跨单位)
--     4 行 批次 盒  / 计划 包  (跨单位)
--     1 行 批次 盒  / 计划 盒  (同单位, efficiency 保留)
--   其中 id 1915 的 efficiency = 87.50, 是 kg 对 盒 的跨单位比值, 本迁移后为 NULL。
--   不命中: 1754 行没挂计划 (留给实体的 null 处理); id 182 指向已不存在的计划。

DO $$
BEGIN
    IF to_regclass('production_batches') IS NOT NULL
       AND to_regclass('production_plans') IS NOT NULL
       AND (SELECT count(*) FROM pg_attribute
             WHERE attrelid = to_regclass('production_batches') AND NOT attisdropped
               AND attname IN ('id', 'production_plan_id', 'planned_quantity', 'planned_unit',
                               'unit', 'efficiency', 'deleted_at')) = 7
       AND (SELECT count(*) FROM pg_attribute
             WHERE attrelid = to_regclass('production_plans') AND NOT attisdropped
               AND attname IN ('id', 'planned_quantity', 'planned_unit')) = 3
    THEN
        UPDATE production_batches b
           SET planned_unit = btrim(p.planned_unit),
               efficiency   = CASE WHEN btrim(b.unit) IS DISTINCT FROM btrim(p.planned_unit)
                                   THEN NULL
                                   ELSE b.efficiency END
          FROM production_plans p
         WHERE p.id = b.production_plan_id
           AND b.planned_unit IS NULL
           AND b.deleted_at IS NULL
           AND b.planned_quantity IS NOT NULL
           AND b.planned_quantity = p.planned_quantity
           AND p.planned_unit IS NOT NULL
           AND btrim(p.planned_unit) <> ''
           AND char_length(btrim(p.planned_unit)) <= 20;
    END IF;
END $$;
