-- ============================================================================
-- 把两处「代码已经假设唯一、库上没有任何载体」的唯一性补成部分唯一索引
--
-- 票: OPS-CONVERSATION-MEMORY-UNIQUENESS-ASSUMED-NOT-ENFORCED-20260907
--
-- 【共同的正当性】⛔ 不是「存量数据里有重复」——两张表现在都 0 重复。
--   理由是**谁依赖它**: 两处的产品代码都已经假设了唯一, 而库上一条载体都没有
--   (实测 pg_indexes ∪ pg_constraint: 两张表只有各自的主键)。
--   ⇒ 一条约束的正当性来自「谁依赖它」, ⛔ 不来自「它今天拦下过几次」。
--
-- ⚠️ 分母(如实报, ⛔ 不拿它当理由):
--     conversation_memory  366 活行 / distinct session_id 366        -> 0 重复
--     labels                15 活行 / distinct label_code 15         -> 0 重复
--   labels 只有 15 行 ⇒ 那个「0 重复」几乎不携带信息, ⛔ 不作为依据。
--
-- 🔴 两条的理由**不同**, 分开写在下面, ⛔ 不要读成同一条。
-- ============================================================================


-- ────────────────────────────────────────────────────────────────────────────
-- §1 labels.label_code —— 应用层**已经在查重**, 索引只是把它变得并发安全
--
-- LabelServiceImpl:95
--     if (labelRepository.existsByLabelCodeAndDeletedAtIsNull(label.getLabelCode()))
--         throw new RuntimeException("标签编码已存在: " + ...);
-- ⇒ 业务规则**已经存在**且是产品行为(会给用户报错), 只是它是 check-then-insert:
--   两个并发请求都查不到、都插入。索引补的正是这一格。
--
-- 🔴【为什么是单列 (label_code) 而不是 (factory_id, label_code)】
--   应用层那个 exists 查询**不带 factory_id** ⇒ 它执行的是**全局唯一**。
--   建 (factory_id, label_code) 会比应用层**松**: 跨厂重码索引放行、而应用层照样拒,
--   ⇒ 那一格仍然只由那个有竞态的应用检查把守, 缺陷原封不动。
--
--   实测支持全局唯一是**故意的**: label_code 的格式里嵌着工厂 id ——
--       LB-F006-20260610124315-7520      00-LIUSHANMEN-20260806151022-5520
--   即 <前缀>-<工厂>-<时间戳>-<随机> ⇒ 跨厂撞码在构造上几乎不可能, 全局唯一不会误伤。
--
--   ⚠️ 现有 15 行数据**分辨不了**这两条规则(global 15 = per_factory 15, 2 个工厂,
--      无任何 label_code 跨厂出现)。所以判据不是数据, 是**应用层写的是哪一条**。
--   ⚠️ 阳性对照(证明「量不出差别」不是尺子坏了): 同一把尺子在 raw_material_types(8 vs 13)、
--      process_sheet_row_change_log(9 vs 11)、process_sheet_rows(7 vs 9)、
--      bom_recipe_items(5 vs 8) 上都量得出 global < per_factory。
-- ────────────────────────────────────────────────────────────────────────────
CREATE UNIQUE INDEX IF NOT EXISTS uk_labels_label_code
    ON labels (label_code)
    WHERE deleted_at IS NULL AND label_code IS NOT NULL;


-- ────────────────────────────────────────────────────────────────────────────
-- §2 conversation_memory.session_id —— 唯一性只编码在**返回类型**里
--
-- ConversationMemoryRepository:30
--     Optional<ConversationMemory> findBySessionId(String sessionId);
-- `Optional` 是一句断言: 这个条件最多命中一行。而库上没有任何东西维护它。
--
-- 🔴 后果不是「没有约束」, 是**约束的检查点被搬到了错误的地方**:
--   失败从**写时**(插入那一刻, 报 duplicate key, 指向真正的原因)
--   推迟到**读时**(下一次有人来查, 报 NonUniqueResultException ——
--   而那个异常**长得像仓库方法写错了**, 下一个人会去改查询, 把最后一个信号也消掉)。
--
-- ✅【补之前答过的那件事: 写入路径怎么处理这个约束冲突】
--   ConversationMemoryServiceImpl.getOrCreateContext:136 是 **get-or-create**:
--       findBySessionId(sessionId) -> 有则 touch, 无则 createNewMemory(...)
--   ⇒ 语义本来就是「同 session 一行」, 冲突**只可能来自竞态**(两个并发请求都查不到、
--     都创建) ⇒ 冲突即幂等冲突, 补索引不改变任何单线程行为。
--   ⇒ 又是一处 check-then-insert(与 DahuaDeviceController:167 同形)。
--   ⚠️ 两个调用方(IntentExecutionOrchestrator:4213 / SmartBIServiceImpl:940)都把这段
--     包在只 log.warn 的 try/catch 里 ⇒ 约束冲突**不会变成 500**。
--   ⚠️ sessionId 为空时走 UUID.randomUUID()(:547) ⇒ 不会自造撞键。
--
-- ⚠️ 谓词带 session_id IS NOT NULL: PG 的 NULLS DISTINCT 本来就不约束 NULL,
--   写出来是为了让「NULL 不参与」成为一句**读得到的话**, ⛔ 不靠读者知道那条语义。
-- ────────────────────────────────────────────────────────────────────────────
CREATE UNIQUE INDEX IF NOT EXISTS uk_conversation_memory_session_id
    ON conversation_memory (session_id)
    WHERE deleted_at IS NULL AND session_id IS NOT NULL;


-- ────────────────────────────────────────────────────────────────────────────
-- 📌 订正 V20261101_40 / PR #741 正文里的一句话(那边已合, PR body 改不了 ⇒ 记在这里)
--
-- 我在那支迁移的 §1 里写「production_reports 150 行 ⇒ 本批优先级最高」。**理由站不住**:
--   实测 total_rows=150, 而 live_no_batch = 0 ——
--   那 150 行**全都 batch_id NOT NULL**, 而 uk_report_worker_type_date_no_batch 的谓词是
--   `WHERE deleted_at IS NULL AND batch_id IS NULL` ⇒ 它当前约束的是 **0 行**。
-- 索引本身没错(那条路径确实存在, python seed 的 _assert_no_batch_report_uniqueness
-- 就是为它写的补偿), 错的是我**把整张表的行数当成了那条索引的分母**。
-- ⇒ 报一个数当理由时, 要报「被这条规则覆盖的那部分」的数, ⛔ 不是整张表的数。
-- ────────────────────────────────────────────────────────────────────────────
