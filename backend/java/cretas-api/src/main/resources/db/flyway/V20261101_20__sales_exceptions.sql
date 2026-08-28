-- V20261101_20: 销售异常单表 —— 记录【被拦下的超发尝试】
--
-- ⚠️ 本文件原编号 V20261101_10, 因与 edb418f3a6 (PR #319) 的
--    V20261101_10__bom_recipe_item_semi_finished_row.sql 撞号而改成 _20。
--    撞号发生在开工【之后】—— 开工时 origin/main 上 _10 是空的, 两小时后就被占了。
--    这与 V20261101_03 头部记的是同一个教训, 而且是同一周内第二次:
--    ⛔ git 对 Flyway 撞号一个字都不报(文件名不同), 只有 FlywayVersionUniquenessTest 会红,
--       而那个检查的保质期只有几十分钟。开 PR 前必须 rebase 到最新 origin/main 再跑一次。
--    ⇒ 这次特意跳到 _20 而不是顺延 _11: 并行分支在按 04/05/10 往上走,
--      留一段空号比抢下一个号更省事。
--
-- ## 补的是什么洞
--   V20261101_03 + SalesServiceImpl.assertWithinOverShipCap 上线了超发上限闸,
--   但那道闸【只拦不留痕】: 超限抛 409, 之后没有任何记录说明
--   「谁 / 什么时候 / 在哪张发货单上 / 想超发多少 / 被哪条上限拦下」。
--
--   采购侧早有 purchase_exceptions (V20261010_07)。销售侧此前两样都没有,
--   V20261101_03 补了「闸」, 本迁移补「留痕」。
--
-- ## 🔴 为什么新建一张表, 而不是复用 purchase_exceptions
--   判据是「那张表的字段语义是不是绑死在采购单/入库单上」—— 读完之后答案是【绑死了】:
--
--     receive_record_id  VARCHAR(191) NOT NULL   ← 入库记录, 销售侧没有这个东西
--     purchase_order_id  VARCHAR(191) NOT NULL   ← 采购单,   销售侧没有这个东西
--     supplier_id / material_type_id / material_name        ← 供应商 + 物料
--     po_quantity / received_quantity                       ← 「下单量 / 实收量」
--     decision           ACCEPT_OVER/RETURN_OVER/ACCEPT_UNDER/REORDER
--     return_order_id                                       ← PURCHASE_RETURN 退货单
--     owner_user_id      ← 责任人 = 采购单创建人; 主管角色写死 procurement_manager
--
--   两个 NOT NULL 列在销售链上没有对应物, 复用就只能往里塞假值或把它们改成可空 ——
--   而「把别人的 NOT NULL 改成可空」正是让那张表对采购侧也不再有约束力。
--
--   更硬的一条是**生命周期不同**:
--     采购异常 = 货【已经收进来了】, 是既成事实, 所以才需要 ACCEPT/RETURN 决策 + 退货单;
--     销售异常 = 发货【被拒绝了】, 什么都没发生, 没有东西可决策、可退。
--   ⇒ 它们不是同一个东西, 强行合表会让 decision / return_order_id / status 这一整套
--     对销售侧的行永远为 null, 而查询方无从知道「null 是因为还没决策还是因为不适用」。
--
--   ⚠️ 放弃复用的代价 (显式登记, 不是没想到):
--     1. 「异常单」这个概念在库里有两张表。日后要一个统一的异常工作台就得 UNION 两张表,
--        或者建一个视图。
--     2. 单号生成 / 唯一索引 / 软删这套写法有两份, 会漂。
--        —— 本迁移【故意不照抄】采购那份 (见下面的唯一索引说明), 所以第一天就已经是漂的,
--           但漂的方向是「销售这份修好了采购那份的缺陷」。
--        采购侧那份该不该跟着修, 不在本任务范围内, 留给动 purchase 链的人。
--
-- ## 🔴 唯一索引【故意不带 WHERE deleted_at IS NULL】—— 与采购侧的写法不同
--   本仓软删全局生效 (BaseEntity @SQLDelete + 每个实体 @Where(deleted_at IS NULL)),
--   已记录 57 个发号器里 20 个是坏的, 坏法都是同一个:
--   「这个号被占用了吗」被答成「有没有一行【活着的】记录用了它」。
--
--   采购侧 uidx_purchase_exceptions_number 带了 WHERE deleted_at IS NULL,
--   于是一个号被软删之后【可以被重新发出去】—— 历史行与新行同号, 追溯时指向两条记录。
--   (采购侧的 generateExceptionNumber 更进一步: 它数的是 countPendingByFactoryId,
--    只要有一张异常单被决策成 RESOLVED, 下一个号就会退回去撞上已存在的号。)
--
--   本表的唯一索引是【全量】的: 软删的行仍然占着它的号, 号一旦发出永不回收。
--   配套的取号查询也走 native SQL 直接读本表 (native 不经过 @Where),
--   既不看 deleted_at 也不看 status —— 见 SalesExceptionRepository.findHighestNumberForPrefix。

CREATE TABLE IF NOT EXISTS sales_exceptions (
    id                    VARCHAR(191)   NOT NULL PRIMARY KEY,
    factory_id            VARCHAR(191)   NOT NULL,
    exception_number      VARCHAR(50)    NOT NULL,

    -- 出事的那张发货单 / 那一行
    delivery_record_id    VARCHAR(191)   NOT NULL,
    delivery_number       VARCHAR(50),
    delivery_item_id      BIGINT,
    sales_order_id        VARCHAR(191),
    product_type_id       VARCHAR(191),
    product_name          VARCHAR(200),

    -- 异常类型: 今天只有 OVER_SHIP_BLOCKED (超发被拦)
    exception_type        VARCHAR(32)    NOT NULL,

    -- 数量三件套 (⚠️ attempted 是【想发的】, 不是【发出去的】—— 这一单没发出去)
    planned_quantity      NUMERIC(15,4)  NOT NULL,
    attempted_quantity    NUMERIC(15,4)  NOT NULL,
    max_allowed_quantity  NUMERIC(15,4),
    unit                  VARCHAR(20),

    -- 当时生效的上限, 及它是从哪来的 (OverReceiveLimit.rate() / .source())
    -- ⚠️ 没有 limit_source 就分不清「上限 0.10」是工厂配的还是全局兜底的,
    --    而排查一条投诉时这一行往往就是全部答案。
    limit_rate            NUMERIC(9,6),
    limit_source          VARCHAR(120)   NOT NULL,

    -- 状态: 今天只有 BLOCKED。⚠️ 这里【故意】没有 decision / decided_by 那一套 ——
    -- 发货被拒绝了, 没有既成事实可决策。硬加一个恒为 null 的决策列只会让人误以为有流程。
    status                VARCHAR(32)    NOT NULL DEFAULT 'BLOCKED',

    -- 谁在什么时候试的
    attempted_by          BIGINT,
    attempted_at          TIMESTAMP      NOT NULL DEFAULT NOW(),

    -- BaseEntity audit
    created_at            TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP      NOT NULL DEFAULT NOW(),
    deleted_at            TIMESTAMP
);

-- 🔴 全量唯一 (⛔ 不加 WHERE deleted_at IS NULL, 理由见文件头)
CREATE UNIQUE INDEX IF NOT EXISTS uidx_sales_exceptions_number
    ON sales_exceptions (factory_id, exception_number);

-- 取号查询走这个索引: WHERE factory_id = ? AND exception_number LIKE '前缀%'
-- (uidx 已经覆盖这个前缀查询, 不再单建。)

CREATE INDEX IF NOT EXISTS idx_sales_exceptions_factory_time
    ON sales_exceptions (factory_id, attempted_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_sales_exceptions_delivery
    ON sales_exceptions (delivery_record_id)
    WHERE deleted_at IS NULL;

COMMENT ON TABLE  sales_exceptions                      IS '销售异常单: 被拦下的超发尝试 (只留痕, 无决策流程)';
COMMENT ON COLUMN sales_exceptions.exception_type       IS 'OVER_SHIP_BLOCKED = 实发超出超发上限被拒绝';
COMMENT ON COLUMN sales_exceptions.attempted_quantity   IS '仓管【想】填的实发量 —— 这一单被 409 拒绝了, 库存没有按它扣';
COMMENT ON COLUMN sales_exceptions.max_allowed_quantity IS '当时的上限值 = planned × (1 + limit_rate)';
COMMENT ON COLUMN sales_exceptions.limit_source         IS 'OverReceiveLimit.source(): threshold-hub / property / sales-unconfigured';
COMMENT ON COLUMN sales_exceptions.status               IS '今天只有 BLOCKED; ⛔ 本表刻意没有决策流程';
