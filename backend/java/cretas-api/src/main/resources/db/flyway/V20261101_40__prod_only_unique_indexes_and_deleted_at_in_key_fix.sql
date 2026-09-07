-- ============================================================================
-- prod-only 唯一索引补进版本控制 + 4 条「deleted_at 写进键里」改成部分索引
--
-- 票: OPS-PROD-ONLY-PARTIAL-INDEXES-20260907 (第二批; 第一批是 V20261101_39)
--
-- 【怎么找出来的】⛔ 不是「碰巧发现几条」, 是把普查按**收窄后的扫描根**整个重跑一遍。
--   V20261101_39 之后, 闸里 PROD_ONLY_NOT_IN_MIGRATIONS 是 Set.of() —— 读起来像「全清了」,
--   而它是**过期读数**: 那次普查扫的是整棵 db/ 树, 死目录(migration/ 等 442 个永不执行的文件)
--   里的语句被当成「迁移里有」。闸自己的 javadoc 就点名了
--   uk_report_worker_type_date_no_batch 是这么被污染掉的 —— 扫描根收窄修好了闸的扫描,
--   **但这个普查集合没有跟着重算**。
--
-- 【重跑的读数】prod public 非主键唯一索引 310 条 =
--   FULL 199 / PARTIAL 104 / DELETED_AT_IN_KEY 4 / OTHER_PARTIAL 3
--   其中「prod 有、活迁移目录(db/flyway)里最终没有」的:
--     DELETED_AT_IN_KEY  4 / 4    <- 本文件 §2, 且形态是错的
--     PARTIAL            2 / 104  <- uk_report_worker_type_date_no_batch (本文件 §1)
--                                    uk_encoding_rules (已判良性, 见闸里 PROD_ONLY_BENIGN, ⛔ 本文件不动它)
--     OTHER_PARTIAL      1 / 3    <- uk_sm_published (本文件 §3)
--   ⚠️ FULL 桶 189/199 是**解析器的盲区**, ⛔ 不是 189 个缺口: CREATE TABLE 里的内联
--      UNIQUE(...) 由 PG 自动命名(xxx_key), 本次普查按名字比对看不见它们。这一格另立票。
-- ============================================================================


-- ────────────────────────────────────────────────────────────────────────────
-- §1 production_reports: 同工人同类型同日、无批次时只允许一条
--
-- prod 上一直有, 迁移里没有 ⇒ 从迁移新建的库**缺这条并发防重**。
-- ⚠️ 这张表是本批里唯一有真实业务量的: public.production_reports 150 行
--    (dahua/isapi 都是 0 行) ⇒ 优先级最高的就是它。
-- 同族的 uk_report_worker_batch_type_date_non_yield 活迁移里**已经有了**
-- (V20260901_03:60), ⛔ 不重复建。
-- ────────────────────────────────────────────────────────────────────────────
CREATE UNIQUE INDEX IF NOT EXISTS uk_report_worker_type_date_no_batch
    ON production_reports (factory_id, worker_id, report_type, report_date)
    WHERE deleted_at IS NULL AND batch_id IS NULL;


-- ────────────────────────────────────────────────────────────────────────────
-- §2 大华 / Isapi 四条: deleted_at 在**键里**, 对活行零约束
--
-- 🔴 PG 默认 NULLS DISTINCT: 活行的 deleted_at 恒为 NULL ⇒ 键里含 NULL ⇒
--    两条完全相同的活行**不冲突**。实测(一次性 pgvector:pg17 容器, 用 prod 原样 DDL):
--      · 两条相同的**软删**行(deleted_at 非空) => duplicate key  <- 阳性对照, 尺子会拒东西
--      · 三条完全相同的**活行**              => 全部插入成功    <- 🔴 零约束
--      · 同数据换成部分索引                  => 第二条被拒      <- 修法有效
--    ⇒ 这个键把约束加在了**唯一不需要约束的那一半**(软删行)上。
--
-- ⚠️ 「表是空的所以不急」是错的读数: pg_stat_user_tables 显示
--    isapi_devices inserts=12/deletes=60, isapi_device_channels inserts=6/deletes=10
--    ⇒ 写过, 然后清空了。⛔ 不是「还没启用」。(dahua 两张确实 0/0。)
--
-- 🔴【为什么是 DROP CONSTRAINT 而不是 DROP INDEX】2026-09-07 在 prod schema 副本上真跑一次才发现：
--    这 4 个名字在 prod 上是 **UNIQUE CONSTRAINT**（pg_constraint.contype='u'），不是裸索引。
--      psql: cannot drop index uk_dahua_factory_ip_port because constraint ... requires it
--    ⚠️ `pg_indexes` 对「约束背后的索引」和「裸索引」**显示完全相同**（都是 CREATE UNIQUE INDEX ...）
--       ⇒ 光查 pg_indexes 分不出来，我此前所有读数都把两者混为一谈。
--       分母：prod public 有 181 个唯一约束 / 310 条非主键唯一索引 ⇒ 混淆面很大，⛔ 不是个别情况。
--    ⚠️ 而且 PG 的 UNIQUE CONSTRAINT **不能是部分的**（不支持 WHERE）
--       ⇒ 想要部分唯一，就只能是**裸索引**，约束必须先摘掉。
--    ⇒ 这也是为什么本文件先 ALTER TABLE ... DROP CONSTRAINT 再 DROP INDEX：
--      两条都带 IF EXISTS，覆盖「是约束」和「是裸索引」两种环境，⛔ 不假设它一定是哪种。
--
-- 【顺序】先 DROP 再 CREATE 同名。Flyway 在 PG 上每支迁移跑在一个事务里 ⇒
-- 若 CREATE 失败, 前面的 DROP 会**一起回滚**, ⛔ 不会留下「旧的删了新的没建」的裸奔窗口。
-- ✅ 这一条是**实测**, ⛔ 不是「按 PG 语义应当如此」: 本文件第一版把 4 条写成 DROP INDEX,
--    在 prod schema 副本上跑到第 2 节就炸(那 4 个是约束不是裸索引, 见上)。
--    炸掉之后复查 6 条索引的形态 —— **一条没变**, 包括那条已经成功执行过的 CREATE。
--    ⇒ 一次失败顺手把这条保证从推断变成了读数。
-- 而失败本身是**要的**: 有重复活行时就该炸出来, ⛔ 不降级、不静默跳过。
-- ────────────────────────────────────────────────────────────────────────────
ALTER TABLE dahua_devices DROP CONSTRAINT IF EXISTS uk_dahua_factory_ip_port;
DROP INDEX IF EXISTS uk_dahua_factory_ip_port;
CREATE UNIQUE INDEX uk_dahua_factory_ip_port
    ON dahua_devices (factory_id, ip_address, port)
    WHERE deleted_at IS NULL;

ALTER TABLE dahua_device_channels DROP CONSTRAINT IF EXISTS uk_dahua_device_channel;
DROP INDEX IF EXISTS uk_dahua_device_channel;
CREATE UNIQUE INDEX uk_dahua_device_channel
    ON dahua_device_channels (device_id, channel_id)
    WHERE deleted_at IS NULL;

ALTER TABLE isapi_devices DROP CONSTRAINT IF EXISTS uk_factory_ip_port;
DROP INDEX IF EXISTS uk_factory_ip_port;
CREATE UNIQUE INDEX uk_factory_ip_port
    ON isapi_devices (factory_id, ip_address, port)
    WHERE deleted_at IS NULL;

ALTER TABLE isapi_device_channels DROP CONSTRAINT IF EXISTS uk_device_channel;
DROP INDEX IF EXISTS uk_device_channel;
CREATE UNIQUE INDEX uk_device_channel
    ON isapi_device_channels (device_id, channel_id)
    WHERE deleted_at IS NULL;


-- ────────────────────────────────────────────────────────────────────────────
-- §3 state_machines: 每厂每实体类型只能有一条 published
--
-- prod-only。⚠️ 它的谓词是 publish_status = 'published', **不是** deleted_at IS NULL
-- ⇒ ⛔ 不要把它登记进 PartialUniqueIndexBackingSoftDeleteContractTest 的
--    MUST_BE_PARTIAL_IN_MIGRATIONS: 那道闸判 partial 的依据就是语句里含
--    "deleted_at is null"(该类第 519 行), 登进去会被判成 full 而报红 —— 闸没错, 是分类错。
-- ⚠️ StateMachine 继承 BaseEntity、有 deletedAt, 但 @SQLDelete = 0
--    ⇒ 它是 R68 那批**物理删**实体之一, 属于将来的软删批, 本票只补它的索引。
-- ────────────────────────────────────────────────────────────────────────────
CREATE UNIQUE INDEX IF NOT EXISTS uk_sm_published
    ON state_machines (factory_id, entity_type)
    WHERE publish_status = 'published';
