-- =====================================================================
-- 批次追溯闭包: 补上「祖先 × 后代」交叉组合 (2026-10-25)
--
-- 缺陷: V20260821_02 的 fn_maintain_lineage_closure 在插入边 S→T 时只写 5 步:
--   ① S 自身  ② T 自身  ③ S→T  ④ anc(S)→T  ⑤ S→desc(T)
-- 缺了第 ⑥ 步 anc(S)×desc(T)。边按「上游先、下游后」写入时 desc(T) 为空, 看不出来;
-- 但在**两段已有链的中间补一条边**时, 上游段的祖先不会成为下游段的祖先。
-- 历史回填 (PR #1147 / #1156 的 LineageBackfillService 写 PRODUCTION_TO_WIP / App 领料边)
-- 正是这种写法 ⇒ prod 实测 LIUSHANMEN 生产批 10898 的闭包祖先只有 4 个、最深 2,
-- 而按边集递归应能到约 19 层、到首道原料 CMR-20260831-753973766C24。
--
-- 本迁移两件事, 都只增不改:
--   1. CREATE OR REPLACE 闭包维护函数, 增加第 ⑥ 步 (触发器 trg_lineage_edge_insert 按函数名绑定, 不用重建)。
--   2. 从 batch_lineage_edges 重算每个工厂的传递闭包, 把 closure 里缺的 (祖先, 后代) 对补进去。
--      ⛔ 不 DELETE、不 UPDATE 已有行 (closure 是只追加的审计表, 见 BatchLineageClosure#preRemove)。
--
-- ---------------------------------------------------------------------
-- depth 语义 (⚠️ 读之前先看这一段)
-- ---------------------------------------------------------------------
-- depth = 写入那一刻找到的那条路径的跳数; **不保证是最短路径**:
--   * 触发器各步都是 ON CONFLICT DO NOTHING ⇒ 同一对 (祖先, 后代) 先写进来的 depth 赢, 之后
--     出现更短的路径也不会改它 (本迁移保持这个只增口径, 不 UPDATE)。
--   * 第 ⑥ 步给 anc.depth + 1 + desc.depth, 即经过新边 S→T 的那条路径的长度。
--   * 本迁移的补齐行取边集上的**最短**跳数 (MIN), 但已有行不动 —— 已有行可能比最短路径长。
-- 依赖 depth 的现有代码 (都把它当「跳数」用, 没有地方要求它精确等于最短路径):
--   * BatchLineageClosureRepository.findFullGraph: `depth <= maxDepth` 过滤 ——
--     LineageGraphAssembler (溯源页 / AI LineageQueryTool) 与 CanvasIndicatorsController.batchLineage
--     都把 maxDepth 夹到 ≤ 10。⇒ 补齐后 depth > 10 的祖先 (例如 10898 那条约 19 层的链) 在这三个
--     入口里**仍然看不到**; 那是展示上限的事, 不是闭包缺行, 本迁移不改它。
--   * LineageGraphAssembler.groupByDepth: 按 depth 分组成「第 N 跳」展示; depth 偏大只会把节点放到更远的一层。
--   * depth = 0 是自引用行 (各入口都跳过), depth > 0 是链上的点 —— 这条语义本迁移保持不变。
--
-- ---------------------------------------------------------------------
-- 防环 / 幂等
-- ---------------------------------------------------------------------
-- * 边集理论上无环, 但 WIP_CONSUME 有大量 source = target 的自环行 (prod 实测 67 条里 48 条) ——
--   递归里先排除自环; 万一存在真环, UNION 去重 (含 depth 列) + 跳数上限 1000 保证递归终止
--   (无环时递归在最长链处自然停止, 上限碰不到; 有环时任何可达对都已经在 ≤ 节点数 跳内出现过)。
-- * 补齐只写 祖先 ≠ 后代 的对; 自引用 depth 0 行由 ⓪ 单独补 (正常情况下本来就有)。
-- * 全部 ON CONFLICT DO NOTHING ⇒ 重跑本文件的结果与跑一次相同。
--
-- ---------------------------------------------------------------------
-- 与蓝绿发布并发
-- ---------------------------------------------------------------------
-- 新槽跑迁移时旧槽仍可能在写边 (旧函数没有第 ⑥ 步)。先 LOCK edges SHARE MODE:
-- 与 INSERT 的 ROW EXCLUSIVE 冲突 ⇒ 迁移提交前别人的写边排队等, 提交后它们走的是新函数;
-- 本迁移读到的边集与补齐之间不会再插进一条「旧函数写的边」。边由 LineageEdgeWriter 在业务事务
-- 提交后的独立事务里写, 排队只推迟边的落库, 不挡业务提交。表量级 (prod 225 行) 下锁持有时间可忽略。
-- =====================================================================

LOCK TABLE batch_lineage_edges IN SHARE MODE;

-- ---------------------------------------------------------------------
-- 1. 闭包维护函数: 原 5 步逐字保留 + 第 ⑥ 步
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_maintain_lineage_closure()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    -- 1. Self-ref source (depth 0)
    INSERT INTO batch_lineage_closure
        (id, factory_id, ancestor_type, ancestor_id, descendant_type, descendant_id, depth, created_at, updated_at)
    VALUES
        (gen_random_uuid()::VARCHAR, NEW.factory_id, NEW.source_type, NEW.source_id,
         NEW.source_type, NEW.source_id, 0, NOW(), NOW())
    ON CONFLICT (factory_id, ancestor_id, ancestor_type, descendant_id, descendant_type) DO NOTHING;

    -- 2. Self-ref target (depth 0)
    INSERT INTO batch_lineage_closure
        (id, factory_id, ancestor_type, ancestor_id, descendant_type, descendant_id, depth, created_at, updated_at)
    VALUES
        (gen_random_uuid()::VARCHAR, NEW.factory_id, NEW.target_type, NEW.target_id,
         NEW.target_type, NEW.target_id, 0, NOW(), NOW())
    ON CONFLICT (factory_id, ancestor_id, ancestor_type, descendant_id, descendant_type) DO NOTHING;

    -- 3. Direct edge (depth 1)
    INSERT INTO batch_lineage_closure
        (id, factory_id, ancestor_type, ancestor_id, descendant_type, descendant_id, depth, created_at, updated_at)
    VALUES
        (gen_random_uuid()::VARCHAR, NEW.factory_id, NEW.source_type, NEW.source_id,
         NEW.target_type, NEW.target_id, 1, NOW(), NOW())
    ON CONFLICT (factory_id, ancestor_id, ancestor_type, descendant_id, descendant_type) DO NOTHING;

    -- 4. All ancestors of source become ancestors of target at depth+1
    INSERT INTO batch_lineage_closure
        (id, factory_id, ancestor_type, ancestor_id, descendant_type, descendant_id, depth, created_at, updated_at)
    SELECT gen_random_uuid()::VARCHAR, NEW.factory_id, anc.ancestor_type, anc.ancestor_id,
           NEW.target_type, NEW.target_id, anc.depth + 1, NOW(), NOW()
    FROM batch_lineage_closure anc
    WHERE anc.factory_id = NEW.factory_id
      AND anc.descendant_id = NEW.source_id
      AND anc.descendant_type = NEW.source_type
      AND anc.depth > 0
    ON CONFLICT (factory_id, ancestor_id, ancestor_type, descendant_id, descendant_type) DO NOTHING;

    -- 5. Source becomes ancestor of all existing descendants of target at depth+1
    INSERT INTO batch_lineage_closure
        (id, factory_id, ancestor_type, ancestor_id, descendant_type, descendant_id, depth, created_at, updated_at)
    SELECT gen_random_uuid()::VARCHAR, NEW.factory_id, NEW.source_type, NEW.source_id,
           desc_.descendant_type, desc_.descendant_id, desc_.depth + 1, NOW(), NOW()
    FROM batch_lineage_closure desc_
    WHERE desc_.factory_id = NEW.factory_id
      AND desc_.ancestor_id = NEW.target_id
      AND desc_.ancestor_type = NEW.target_type
      AND desc_.depth > 0
    ON CONFLICT (factory_id, ancestor_id, ancestor_type, descendant_id, descendant_type) DO NOTHING;

    -- 6. Every ancestor of source becomes ancestor of every existing descendant of target
    --    (cross product; depth = anc.depth + 1 + desc.depth = the path through this edge, not
    --    necessarily the shortest one — see the header). Without this step, inserting an edge
    --    between two already-built chain segments leaves the upstream segment's ancestors
    --    disconnected from the downstream segment.
    INSERT INTO batch_lineage_closure
        (id, factory_id, ancestor_type, ancestor_id, descendant_type, descendant_id, depth, created_at, updated_at)
    SELECT gen_random_uuid()::VARCHAR, NEW.factory_id, anc.ancestor_type, anc.ancestor_id,
           desc_.descendant_type, desc_.descendant_id, anc.depth + 1 + desc_.depth, NOW(), NOW()
    FROM batch_lineage_closure anc
    JOIN batch_lineage_closure desc_
      ON desc_.factory_id = NEW.factory_id
     AND desc_.ancestor_id = NEW.target_id
     AND desc_.ancestor_type = NEW.target_type
     AND desc_.depth > 0
    WHERE anc.factory_id = NEW.factory_id
      AND anc.descendant_id = NEW.source_id
      AND anc.descendant_type = NEW.source_type
      AND anc.depth > 0
      AND NOT (anc.ancestor_id = desc_.descendant_id AND anc.ancestor_type = desc_.descendant_type)
    ON CONFLICT (factory_id, ancestor_id, ancestor_type, descendant_id, descendant_type) DO NOTHING;

    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------
-- 2. 一次性只增补齐: 从边集重算传递闭包, 补 closure 里缺的对
-- ---------------------------------------------------------------------
-- ⓪ 自引用 depth 0 (每个出现在边上的节点一行; 正常情况下已经有, 这里只兜住缺的)
INSERT INTO batch_lineage_closure
    (id, factory_id, ancestor_type, ancestor_id, descendant_type, descendant_id, depth, created_at, updated_at)
SELECT gen_random_uuid()::VARCHAR, n.factory_id, n.node_type, n.node_id, n.node_type, n.node_id, 0, NOW(), NOW()
FROM (
    SELECT factory_id, source_type AS node_type, source_id AS node_id FROM batch_lineage_edges
    UNION
    SELECT factory_id, target_type, target_id FROM batch_lineage_edges
) n
ON CONFLICT (factory_id, ancestor_id, ancestor_type, descendant_id, descendant_type) DO NOTHING;

-- ① 祖先 ≠ 后代 的可达对, depth 取边集上的最短跳数
INSERT INTO batch_lineage_closure
    (id, factory_id, ancestor_type, ancestor_id, descendant_type, descendant_id, depth, created_at, updated_at)
WITH RECURSIVE e AS (
    SELECT DISTINCT factory_id, source_type, source_id, target_type, target_id
    FROM batch_lineage_edges
    WHERE NOT (source_id = target_id AND source_type = target_type)          -- 排除 WIP_CONSUME 自环
),
reach (factory_id, anc_type, anc_id, desc_type, desc_id, depth) AS (
    SELECT factory_id, source_type, source_id, target_type, target_id, 1
    FROM e
    UNION                                                                     -- 含 depth 去重: 真环也只到上限为止
    SELECT r.factory_id, r.anc_type, r.anc_id, e.target_type, e.target_id, r.depth + 1
    FROM reach r
    JOIN e ON e.factory_id = r.factory_id
          AND e.source_id = r.desc_id
          AND e.source_type = r.desc_type
    WHERE r.depth < 1000
),
pairs AS (
    SELECT factory_id, anc_type, anc_id, desc_type, desc_id, MIN(depth) AS depth
    FROM reach
    WHERE NOT (anc_id = desc_id AND anc_type = desc_type)
    GROUP BY factory_id, anc_type, anc_id, desc_type, desc_id
)
SELECT gen_random_uuid()::VARCHAR, p.factory_id, p.anc_type, p.anc_id, p.desc_type, p.desc_id, p.depth, NOW(), NOW()
FROM pairs p
ON CONFLICT (factory_id, ancestor_id, ancestor_type, descendant_id, descendant_type) DO NOTHING;
