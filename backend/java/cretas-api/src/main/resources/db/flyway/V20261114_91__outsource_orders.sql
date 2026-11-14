-- 原生委外闭环 (原型统一 M7 补范围, 设计 docs/specs/outsource-closed-loop-design.md)。
--   模型: 委外单 (按委外厂) + 任务行 (工单/工序可空: PROCESS 工序委外 / WHOLE 整单委外) + 收发明细。
--   库存真值在 material_batches / finished_goods_batches; 本组表是单据与审计, 累计量由服务端维护。
--   物权: 发出的料留在该委外厂专属 OUTSOURCE 仓 (factory_warehouses.outsourcer_supplier_id), 所有权不变。
-- 新表, 不回填。IF NOT EXISTS: 可重复执行。

CREATE TABLE IF NOT EXISTS outsource_orders (
    id                      VARCHAR(64)  PRIMARY KEY,
    factory_id              VARCHAR(64)  NOT NULL,
    order_no                VARCHAR(32)  NOT NULL,
    supplier_id             VARCHAR(191) NOT NULL,
    outsource_warehouse_id  VARCHAR(64),
    status                  VARCHAR(16)  NOT NULL CHECK (status IN ('DRAFT', 'RELEASED', 'CLOSED', 'CANCELLED')),
    expected_return_date    DATE,
    remark                  VARCHAR(500),
    released_at             TIMESTAMP,
    released_by             BIGINT,
    closed_at               TIMESTAMP,
    closed_by               BIGINT,
    created_by              BIGINT       NOT NULL,
    version                 BIGINT       NOT NULL DEFAULT 0,
    created_at              TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted_at              TIMESTAMP,
    CONSTRAINT uk_outsource_orders_no UNIQUE (factory_id, order_no)
);
CREATE INDEX IF NOT EXISTS idx_outsource_orders_supplier ON outsource_orders (factory_id, supplier_id, status);

CREATE TABLE IF NOT EXISTS outsource_order_lines (
    id                        VARCHAR(64)   PRIMARY KEY,
    factory_id                VARCHAR(64)   NOT NULL,
    order_id                  VARCHAR(64)   NOT NULL,
    line_no                   INTEGER       NOT NULL,
    outsource_type            VARCHAR(16)   NOT NULL CHECK (outsource_type IN ('PROCESS', 'WHOLE')),
    production_plan_id        VARCHAR(191),
    production_batch_id       BIGINT,
    work_process_task_id      BIGINT,
    process_name              VARCHAR(100),
    output_kind               VARCHAR(16)   NOT NULL CHECK (output_kind IN ('FINISHED', 'SEMI')),
    product_type_id           VARCHAR(191)  NOT NULL,
    output_name               VARCHAR(200),
    unit                      VARCHAR(20)   NOT NULL,
    planned_quantity          NUMERIC(18,6) NOT NULL CHECK (planned_quantity > 0),
    unit_fee                  NUMERIC(14,4) NOT NULL CHECK (unit_fee >= 0),
    tax_rate                  NUMERIC(6,4),
    received_good_quantity    NUMERIC(18,6) NOT NULL DEFAULT 0,
    received_defect_quantity  NUMERIC(18,6) NOT NULL DEFAULT 0,
    returned_goods_quantity   NUMERIC(18,6) NOT NULL DEFAULT 0,
    rework_pending_quantity   NUMERIC(18,6) NOT NULL DEFAULT 0,
    fee_amount                NUMERIC(14,2) NOT NULL DEFAULT 0,
    status                    VARCHAR(16)   NOT NULL CHECK (status IN ('OPEN', 'COMPLETED', 'CANCELLED')),
    completed_at              TIMESTAMP,
    version                   BIGINT        NOT NULL DEFAULT 0,
    created_at                TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMP     NOT NULL DEFAULT NOW(),
    deleted_at                TIMESTAMP,
    CONSTRAINT uk_outsource_order_lines_no UNIQUE (order_id, line_no),
    -- 工序委外必须挂回某个工单的某道工序; 整单委外不挂。
    CONSTRAINT ck_outsource_lines_process_link CHECK (
        outsource_type <> 'PROCESS' OR (production_plan_id IS NOT NULL AND work_process_task_id IS NOT NULL)),
    CONSTRAINT ck_outsource_lines_nonneg CHECK (
        received_good_quantity >= 0 AND received_defect_quantity >= 0
        AND returned_goods_quantity >= 0 AND rework_pending_quantity >= 0)
);
CREATE INDEX IF NOT EXISTS idx_outsource_lines_order ON outsource_order_lines (order_id);
CREATE INDEX IF NOT EXISTS idx_outsource_lines_plan ON outsource_order_lines (factory_id, production_plan_id);
CREATE INDEX IF NOT EXISTS idx_outsource_lines_task ON outsource_order_lines (factory_id, work_process_task_id);

CREATE TABLE IF NOT EXISTS outsource_movements (
    id                       VARCHAR(64)   PRIMARY KEY,
    factory_id               VARCHAR(64)   NOT NULL,
    order_id                 VARCHAR(64)   NOT NULL,
    line_id                  VARCHAR(64)   NOT NULL,
    movement_type            VARCHAR(20)   NOT NULL CHECK (movement_type IN (
                                 'ISSUE', 'MATERIAL_RETURN', 'RECEIVE', 'CONSUME', 'GOODS_RETURN', 'LOSS')),
    parent_movement_id       VARCHAR(64),
    biz_date                 DATE          NOT NULL,
    material_type_id         VARCHAR(191),
    product_type_id          VARCHAR(191),
    source_batch_id          VARCHAR(191),
    target_batch_id          VARCHAR(191),
    target_batch_kind        VARCHAR(16)   CHECK (target_batch_kind IN ('MATERIAL', 'FINISHED')),
    quantity                 NUMERIC(18,6) NOT NULL CHECK (quantity >= 0),
    defect_quantity          NUMERIC(18,6),
    unit                     VARCHAR(20)   NOT NULL,
    unit_cost                NUMERIC(14,4),
    amount                   NUMERIC(14,2),
    ap_transaction_id        VARCHAR(191),
    quality_return_order_id  VARCHAR(191),
    reason                   VARCHAR(500),
    client_request_id        VARCHAR(64),
    created_by               BIGINT        NOT NULL,
    created_at               TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMP     NOT NULL DEFAULT NOW(),
    deleted_at               TIMESTAMP,
    CONSTRAINT ck_outsource_movements_loss_reason CHECK (
        movement_type <> 'LOSS' OR (reason IS NOT NULL AND LENGTH(TRIM(reason)) >= 2))
);
CREATE INDEX IF NOT EXISTS idx_outsource_movements_line ON outsource_movements (line_id, movement_type);
CREATE INDEX IF NOT EXISTS idx_outsource_movements_order ON outsource_movements (order_id);
-- 防重复提交: 同一次提交 (client_request_id) 只落一次。
CREATE UNIQUE INDEX IF NOT EXISTS uk_outsource_movements_request
    ON outsource_movements (factory_id, client_request_id) WHERE client_request_id IS NOT NULL;

-- 委外厂专属委外仓: 一个委外厂一个 OUTSOURCE 仓。
ALTER TABLE factory_warehouses ADD COLUMN IF NOT EXISTS outsourcer_supplier_id VARCHAR(191);
CREATE UNIQUE INDEX IF NOT EXISTS uk_factory_warehouses_outsourcer
    ON factory_warehouses (factory_id, outsourcer_supplier_id) WHERE outsourcer_supplier_id IS NOT NULL;

-- 工序委外同步到工序任务: 工单详情 / 进度直接看到这道工序在哪个委外厂。
ALTER TABLE work_process_tasks ADD COLUMN IF NOT EXISTS is_outsourced BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE work_process_tasks ADD COLUMN IF NOT EXISTS outsource_line_id VARCHAR(64);
ALTER TABLE work_process_tasks ADD COLUMN IF NOT EXISTS outsourcer_supplier_id VARCHAR(191);
ALTER TABLE work_process_tasks ADD COLUMN IF NOT EXISTS outsource_unit_fee NUMERIC(14,4);
ALTER TABLE work_process_tasks ADD COLUMN IF NOT EXISTS outsource_fee_amount NUMERIC(14,2);

-- 质检退回委外 (SUBCONTRACT) 接上库存与账: 关联任务行与被退的收货批次。
ALTER TABLE quality_return_orders ADD COLUMN IF NOT EXISTS outsource_line_id VARCHAR(64);
ALTER TABLE quality_return_orders ADD COLUMN IF NOT EXISTS source_batch_id VARCHAR(191);

COMMENT ON TABLE outsource_orders IS '委外单 (按委外厂开), 状态 DRAFT→RELEASED→CLOSED; 结单要求委外厂结存为 0。';
COMMENT ON TABLE outsource_order_lines IS '委外任务行: PROCESS 挂工单工序 / WHOLE 整单委外; 加工费 = 实收合格数 × unit_fee。';
COMMENT ON TABLE outsource_movements IS '委外收发明细: 发料/退料/收货/耗用/退货/损耗; 数量真值在批次表。';
