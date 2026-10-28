-- V20261103_120__production_pot_links.sql
--
-- S3-16 锅次: 每一锅用了什么 (投入) / 出了什么 (产出)。
--
-- 投入 / 产出都是对生产批次<b>已有</b>血缘边 (batch_lineage_edges) 的逐锅分摊, 不是新的扣料或入库:
--   投入 source_type/source_id = 某条「→ 这个生产批次」边的来源节点 (原料批 / 在制批);
--   产出 target_type/target_id = 某条「这个生产批次 →」边的目标节点 (成品批 / 在制批)。
-- 服务端校验: 节点必须真是这个批次的投入/产出, 同节点各锅之和不超过批次级边的数量。
-- 召回按锅收窄的前提是「这个节点在该批次的量已经全部分到锅上」, 分不全就不收窄 (按整批算), 见 PotTraceService。
-- 更正时旧行软删 (deleted_at), 新行插入; 前后值同时写进 production_pot_corrections。

CREATE TABLE IF NOT EXISTS production_pot_inputs (
    id            BIGSERIAL PRIMARY KEY,
    factory_id    VARCHAR(50)   NOT NULL,
    pot_id        BIGINT        NOT NULL REFERENCES production_pots (id),
    source_type   VARCHAR(32)   NOT NULL,
    source_id     VARCHAR(191)  NOT NULL,
    quantity      NUMERIC(18,6) NOT NULL,
    unit          VARCHAR(20),
    created_by    BIGINT,
    created_at    TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP     NOT NULL DEFAULT NOW(),
    deleted_at    TIMESTAMP,
    CONSTRAINT ck_production_pot_inputs_qty CHECK (quantity > 0)
);

CREATE INDEX IF NOT EXISTS idx_production_pot_inputs_pot
    ON production_pot_inputs (factory_id, pot_id) WHERE deleted_at IS NULL;
-- 召回热路径: 哪些锅用了这个原料批
CREATE INDEX IF NOT EXISTS idx_production_pot_inputs_source
    ON production_pot_inputs (factory_id, source_id, source_type) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_production_pot_inputs_active
    ON production_pot_inputs (pot_id, source_type, source_id) WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS production_pot_outputs (
    id            BIGSERIAL PRIMARY KEY,
    factory_id    VARCHAR(50)   NOT NULL,
    pot_id        BIGINT        NOT NULL REFERENCES production_pots (id),
    target_type   VARCHAR(32)   NOT NULL,
    target_id     VARCHAR(191)  NOT NULL,
    quantity      NUMERIC(18,6) NOT NULL,
    unit          VARCHAR(20),
    created_by    BIGINT,
    created_at    TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP     NOT NULL DEFAULT NOW(),
    deleted_at    TIMESTAMP,
    CONSTRAINT ck_production_pot_outputs_qty CHECK (quantity > 0)
);

CREATE INDEX IF NOT EXISTS idx_production_pot_outputs_pot
    ON production_pot_outputs (factory_id, pot_id) WHERE deleted_at IS NULL;
-- 反查: 这个成品批来自哪几锅
CREATE INDEX IF NOT EXISTS idx_production_pot_outputs_target
    ON production_pot_outputs (factory_id, target_id, target_type) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_production_pot_outputs_active
    ON production_pot_outputs (pot_id, target_type, target_id) WHERE deleted_at IS NULL;
