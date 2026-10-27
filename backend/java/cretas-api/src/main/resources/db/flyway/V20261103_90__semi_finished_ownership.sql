-- V20261103_90__semi_finished_ownership.sql
--
-- 缺口 16 跨客户领料 (2026-10-27)
--   semi_finished_inventory 加归属 (与 material_batches 同口径):
--     ownership          COMPANY_OWNED / CUSTOMER_OWNED
--     owner_customer_id  客户所有时的客户 id
--   新行由 IntermediateOwnershipGuard 在入账时从产出计划的 output_ownership 继承;
--   下道领用时领用计划的归属必须与行的归属相同。
--
-- 回填: 每一行按「所有产出过它的计划」定归属, 来源三处:
--   ① 行上的 batch_id → production_batches.production_plan_id
--   ② 逐道录入锚 CLK-SEMI-<计划 id 前 8 位>-… → 该工厂里 id 以此开头的计划 (必须唯一)
--   ③ 这一行名下每条产出流水 (IN, report_id) → 报工的生产批次 → 计划
--   一行的来源计划归属不一致 (真混合池) ⇒ 整个迁移失败, 不猜。
--   没有任何来源计划 ⇒ 公司自有。
--
-- 按产品码共用的行 (画布 semiCode, 如 PTSEMI-LSM-0005) 若判为客户所有, 改名为
--   <产品码>@<客户 id 前 8 位> —— 与新代码的落行规则一致, 否则本厂计划下次产出同一产品码时
--   会撞上这行客户的料被拒。只改「从没被领用过」的行 (只有 IN / 冲销 IN 流水); 被领用过的
--   客户所有共用行 ⇒ 迁移失败, 人工处理 (下道报工 source_wip_no 等处还记着旧号)。
--
-- 全部 IF NOT EXISTS / 幂等: 已回填的行 (ownership 非空) 不再改。

ALTER TABLE semi_finished_inventory
    ADD COLUMN IF NOT EXISTS ownership VARCHAR(32),
    ADD COLUMN IF NOT EXISTS owner_customer_id VARCHAR(191);

COMMENT ON COLUMN semi_finished_inventory.ownership IS
    '缺口 16: 归属 COMPANY_OWNED / CUSTOMER_OWNED, 入账时从产出计划继承; NULL 仅迁移前旧行, 按公司自有读';
COMMENT ON COLUMN semi_finished_inventory.owner_customer_id IS
    '缺口 16: 客户所有时的客户 id';

DO $$
DECLARE
    mixed_rows TEXT;
    ambiguous_anchor TEXT;
    consumed_pool TEXT;
BEGIN
    CREATE TEMP TABLE sfi_owner_src ON COMMIT DROP AS
    WITH src AS (
        -- ① 行上的生产批次
        SELECT s.id AS sfi_id, pb.production_plan_id AS plan_id
          FROM semi_finished_inventory s
          JOIN production_batches pb ON pb.id = s.batch_id
         WHERE s.ownership IS NULL AND pb.production_plan_id IS NOT NULL
        UNION
        -- ② 逐道录入锚
        SELECT s.id, p.id
          FROM semi_finished_inventory s
          JOIN production_plans p
            ON p.factory_id = s.factory_id
           AND s.intermediate_batch_no LIKE 'CLK-SEMI-%'
           AND p.id LIKE split_part(substr(s.intermediate_batch_no, 10), '-', 1) || '%'
         WHERE s.ownership IS NULL
        UNION
        -- ③ 产出流水
        SELECT s.id, pb.production_plan_id
          FROM semi_finished_inventory s
          JOIN semi_finished_inventory_transactions t
            ON t.semi_finished_id = s.id AND t.txn_type = 'IN' AND t.report_id IS NOT NULL
          JOIN production_reports r ON r.id = t.report_id
          JOIN production_batches pb ON pb.id = r.batch_id
         WHERE s.ownership IS NULL AND pb.production_plan_id IS NOT NULL
    )
    SELECT src.sfi_id,
           CASE WHEN p.output_ownership = 'CUSTOMER_OWNED' THEN 'CUSTOMER_OWNED' ELSE 'COMPANY_OWNED' END AS ownership,
           CASE WHEN p.output_ownership = 'CUSTOMER_OWNED' THEN p.customer_id END AS customer_id
      FROM src JOIN production_plans p ON p.id = src.plan_id;

    SELECT string_agg(no, ', ') INTO ambiguous_anchor FROM (
        SELECT s.intermediate_batch_no AS no
          FROM semi_finished_inventory s
          JOIN production_plans p
            ON p.factory_id = s.factory_id
           AND s.intermediate_batch_no LIKE 'CLK-SEMI-%'
           AND p.id LIKE split_part(substr(s.intermediate_batch_no, 10), '-', 1) || '%'
         WHERE s.ownership IS NULL
         GROUP BY s.id, s.intermediate_batch_no HAVING count(*) > 1) a;
    IF ambiguous_anchor IS NOT NULL THEN
        RAISE EXCEPTION '缺口16 回填: 逐道录入锚对应多个计划, 不能判断归属: %', ambiguous_anchor;
    END IF;

    IF EXISTS (SELECT 1 FROM sfi_owner_src
                WHERE ownership = 'CUSTOMER_OWNED' AND (customer_id IS NULL OR customer_id = '')) THEN
        RAISE EXCEPTION '缺口16 回填: 有代工计划缺客户 id, 不能判断半成品归属';
    END IF;

    SELECT string_agg(s.factory_id || '/' || s.intermediate_batch_no, ', ') INTO mixed_rows
      FROM semi_finished_inventory s
     WHERE s.id IN (SELECT sfi_id FROM sfi_owner_src
                     GROUP BY sfi_id
                    HAVING count(DISTINCT ownership || ':' || coalesce(customer_id, '')) > 1);
    IF mixed_rows IS NOT NULL THEN
        RAISE EXCEPTION '缺口16 回填: 以下半成品行混着不同归属的产出, 须人工拆分: %', mixed_rows;
    END IF;

    UPDATE semi_finished_inventory s
       SET ownership = o.ownership, owner_customer_id = o.customer_id
      FROM (SELECT DISTINCT sfi_id, ownership, customer_id FROM sfi_owner_src) o
     WHERE s.id = o.sfi_id AND s.ownership IS NULL;

    UPDATE semi_finished_inventory
       SET ownership = 'COMPANY_OWNED', owner_customer_id = NULL
     WHERE ownership IS NULL;

    -- 客户所有的产品码共用行: 改到新代码会落的那一行号
    CREATE TEMP TABLE sfi_pool_rename ON COMMIT DROP AS
    SELECT s.id, s.factory_id, s.intermediate_batch_no AS old_no,
           s.intermediate_batch_no || '@' || left(s.owner_customer_id, 8) AS new_no
      FROM semi_finished_inventory s
     WHERE s.ownership = 'CUSTOMER_OWNED'
       AND s.intermediate_batch_no NOT LIKE 'CLK-%'
       AND s.intermediate_batch_no NOT LIKE '%@%'
       AND s.intermediate_batch_no !~ '-B[0-9]+-S[0-9]+-[0-9]+$';

    SELECT string_agg(r.factory_id || '/' || r.old_no, ', ') INTO consumed_pool
      FROM sfi_pool_rename r
     WHERE EXISTS (SELECT 1 FROM semi_finished_inventory_transactions t
                    WHERE t.semi_finished_id = r.id
                      AND t.txn_type <> 'IN'
                      AND NOT (t.txn_type = 'REVERSE' AND t.quantity < 0))
        OR EXISTS (SELECT 1 FROM semi_finished_inventory s2
                    WHERE s2.factory_id = r.factory_id AND s2.intermediate_batch_no = r.new_no);
    IF consumed_pool IS NOT NULL THEN
        RAISE EXCEPTION '缺口16 回填: 客户所有的产品码共用行已被领用或新行号已存在, 须人工处理: %', consumed_pool;
    END IF;

    UPDATE semi_finished_inventory s
       SET intermediate_batch_no = r.new_no
      FROM sfi_pool_rename r
     WHERE s.id = r.id;
END $$;

ALTER TABLE semi_finished_inventory
    DROP CONSTRAINT IF EXISTS chk_sfi_owner_consistency;
ALTER TABLE semi_finished_inventory
    ADD CONSTRAINT chk_sfi_owner_consistency CHECK (
        ownership IS NULL
        OR (ownership = 'COMPANY_OWNED' AND owner_customer_id IS NULL)
        OR (ownership = 'CUSTOMER_OWNED' AND owner_customer_id IS NOT NULL)
    );
