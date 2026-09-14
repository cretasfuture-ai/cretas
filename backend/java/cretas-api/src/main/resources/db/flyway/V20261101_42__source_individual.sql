-- ============================================================
-- source_individuals: 通用「源头个体档案」
-- ============================================================
-- 任务: FEAT-SOURCE-INDIVIDUAL-VISION-VERIFY-20260914 (分发卡 B 第 1 条)
--
-- 它是什么:
--   溯源链**最前面**那个有生命周期的个体 —— 一头牛 / 一只鸭 / 一头猪 / 一棵树。
--   与 material_batches(原料批次)的关系是「一个批次可以来自某一个个体」,
--   `material_batches.source_individual_id` 可空 ⇒ 不挂个体的批次(绝大多数)完全不受影响。
--
--   ⚠️ 物种是**字段值**(species), 不是模型名: 这张表对牛业/禽业/水产一视同仁,
--      ⛔ 不要因为第一个用它的客户养牛就往里加牛专属列。养殖场特有的属性走 ext_attributes。
--
-- 为什么 individual_code 与 tag_number 是两列:
--   · individual_code —— **系统内**的档案编号, 工厂内唯一(见下面的部分唯一索引), 由业务方自定。
--   · tag_number      —— **物理标识**(耳标号 / 脚环号 / 芯片号), 拍照核验读到的就是它。
--     它**不保证唯一**: 耳标会脱落重挂、跨场引进时可能撞号。⇒ 只建普通索引, ⛔ 不建唯一索引。
--     by-tag 查询因此可能命中多条, 由 service 层显式处理(返回最近建档的一条 + 计数),
--     ⛔ 不在数据库上假装它唯一。
--
-- dna_verification_status 用 CHECK 而不是 PG enum type:
--   本仓既有做法(见 material_batches.status 等)是 VARCHAR + 应用层枚举; PG enum 加值要
--   ALTER TYPE, 在蓝绿部署下是个坑。四个值: UNSAMPLED(未采样) / SAMPLED(已采样待比对) /
--   MATCHED(比中) / MISMATCH(不符)。
--   ⚠️ 本仓前科(2026-09-04): 往枚举加新值前必须查 **prod 的 CHECK 约束** —— 它可能带外加在
--      prod 上而不在 db/flyway/ 里。这张表是**新建**的, 约束只有这一条, 那个坑不适用;
--      但后续要加第五个值的人请先在 prod 上 \d+ source_individuals 看一眼。
--
-- ⚠️ NOT NULL DEFAULT 列与实体默认值必须成对(本仓 accepts_rework 同型缺陷, #522/#508):
--   Hibernate 对「Java 侧为 null 的字段」是**显式 INSERT NULL**, 不是省略该列 ⇒ DB DEFAULT
--   根本不会生效, 直接撞 23502。本迁移里三个 NOT NULL DEFAULT 列各自都有实体侧默认:
--     dna_verification_status  -> SourceIndividual.dnaVerificationStatus = "UNSAMPLED"
--     version                  -> SourceIndividual.version = 0L (@Version, 结构性防御)
--     created_at / updated_at  -> BaseEntity 的 @PrePersist onCreate()
--   NotNullColumnsHaveEntityDefaultsContractTest 会核这件事。
--
-- ⛔ 不写 CREATE TABLE IF NOT EXISTS: 若某个库已有同名但形状不同的表, IF NOT EXISTS 会
--    静默跳过、然后 Hibernate validate 在别处红, 定位成本翻倍(同 V20261101_36 的口径)。
-- ============================================================

CREATE TABLE source_individuals (
    id                      VARCHAR(191) PRIMARY KEY,
    factory_id              VARCHAR(191) NOT NULL,
    individual_code         VARCHAR(64)  NOT NULL,
    tag_number              VARCHAR(64),
    species                 VARCHAR(32),
    breed                   VARCHAR(64),
    sex                     VARCHAR(16),
    birth_date              DATE,
    origin_farm             VARCHAR(128),
    external_gene_bank_id   VARCHAR(128),
    dna_sample_code         VARCHAR(64),
    dna_verification_status VARCHAR(16)  NOT NULL DEFAULT 'UNSAMPLED',
    sire_individual_id      VARCHAR(191),
    dam_individual_id       VARCHAR(191),
    ext_attributes          JSONB,
    notes                   TEXT,
    version                 BIGINT       NOT NULL DEFAULT 0,
    created_at              TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at              TIMESTAMP(6),
    CONSTRAINT ck_source_individual_dna_status
        CHECK (dna_verification_status IN ('UNSAMPLED', 'SAMPLED', 'MATCHED', 'MISMATCH'))
);

-- 🔴 **部分**唯一索引(`WHERE deleted_at IS NULL`), ⛔ 不是全量唯一约束。
--    理由与本仓软删批 1-3 的口径一致: 软删一行之后同一个编号必须能再建, 否则
--    「删了再建」在生产上被拒。⚠️ 实体侧因此**不声明**任何 @UniqueConstraint /
--    unique = true —— JPA 表达不了部分唯一, 声明了只会让 H2 比 prod 严
--    (见 SoftDeleteEntityDeclaredUniqueContractTest 的类文档)。
CREATE UNIQUE INDEX uk_source_individual_code
    ON source_individuals (factory_id, individual_code)
    WHERE deleted_at IS NULL;

-- 耳标/脚环号: 拍照核验与扫码都按它反查, 非唯一(见上)。
CREATE INDEX idx_source_individual_tag
    ON source_individuals (factory_id, tag_number);

CREATE INDEX idx_source_individual_species
    ON source_individuals (factory_id, species);

-- ── 系谱 (父本 / 母本) ───────────────────────────────────────────────────────
-- 客户的「基因库」是种质/育种方向, 个体档案第一天就要能表达系谱。
--
-- ⛔ FK 用 ON DELETE SET NULL, **绝不级联删**: 删掉一头种公畜不该把它的后代档案一起删掉 ——
--    那是把「父本记录没了」升级成「后代不存在过」。SET NULL 让后代留下, 只是不知道父本是谁,
--    而这正是事实(与本仓一贯口径一致: 读不到就是读不到, ⛔ 不伪造也⛔ 不连坐)。
-- ⚠️ 本表是软删(deleted_at), 物理 DELETE 基本不会发生 ⇒ 这条 FK 实际上主要起
--    **写入时的引用完整性**作用(插一个不存在的父本会被拒), SET NULL 那一支是兜底。
-- ⚠️ FK 不含 factory_id(PK 只有 id), 所以「同厂」这条约束由 service 层校验 ——
--    库上拦得住「不存在的个体」, 拦不住「别厂的个体」, 两道各管一半, 都不能省。
ALTER TABLE source_individuals
    ADD CONSTRAINT fk_source_individual_sire
        FOREIGN KEY (sire_individual_id) REFERENCES source_individuals (id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_source_individual_dam
        FOREIGN KEY (dam_individual_id) REFERENCES source_individuals (id) ON DELETE SET NULL;

CREATE INDEX idx_source_individual_sire
    ON source_individuals (factory_id, sire_individual_id);

CREATE INDEX idx_source_individual_dam
    ON source_individuals (factory_id, dam_individual_id);

COMMENT ON TABLE source_individuals IS
    '源头个体档案: 溯源链最前端有生命周期的个体(牛/鸭/猪/…)。物种是字段值 species, 不是模型名。';
COMMENT ON COLUMN source_individuals.individual_code IS
    '系统内档案编号, 工厂内唯一(部分唯一索引 uk_source_individual_code, 软删后可复用)';
COMMENT ON COLUMN source_individuals.tag_number IS
    '物理标识(耳标/脚环/芯片号)。⚠️ 不保证唯一: 会脱落重挂、跨场引进可能撞号';
COMMENT ON COLUMN source_individuals.dna_verification_status IS
    'UNSAMPLED=未采样 / SAMPLED=已采样待比对 / MATCHED=比中 / MISMATCH=不符 (ck_source_individual_dna_status)';
COMMENT ON COLUMN source_individuals.external_gene_bank_id IS
    '外部基因库/第三方检测机构里的样本主键。本系统不存基因数据本身, 只存这一把外部钥匙。';
COMMENT ON COLUMN source_individuals.sire_individual_id IS
    '父本(同表自引用, 可空)。FK ON DELETE SET NULL —— 删父本不连坐后代。同厂校验在 service 层。';
COMMENT ON COLUMN source_individuals.dam_individual_id IS
    '母本(同表自引用, 可空)。同上。';

-- ── material_batches 挂钩(加法式, 可空) ─────────────────────────────────────
-- ⛔ 不加 NOT NULL, ⛔ 不加 DEFAULT: 绝大多数原料批次(面粉/包材/调料)根本没有「源头个体」
--    这个概念, 给它一个默认值就是把「不适用」翻译成一个看起来正常的值。
-- ⛔ 也不加 FK: 本仓 material_batches 上的既有外键列(supplier_id / material_type_id)
--    同样没有 FK 约束, 这里保持一致, 不单独长出一种写法。
ALTER TABLE material_batches
    ADD COLUMN IF NOT EXISTS source_individual_id VARCHAR(191);

COMMENT ON COLUMN material_batches.source_individual_id IS
    '来源个体档案 ID (source_individuals.id)。可空 —— 只有按个体投产的批次(屠宰分割等)才挂。';

-- 部分索引: 只索引真的挂了个体的那些行(绝大多数批次这一列是 NULL)。
CREATE INDEX idx_material_batch_source_individual
    ON material_batches (source_individual_id)
    WHERE source_individual_id IS NOT NULL;

-- 断言: 建出来的列集合必须与实体 SourceIndividual(+BaseEntity) 一一对上; 不对就红着停。
-- (同 V20261101_36 的做法 —— Flyway 单事务, RAISE EXCEPTION 会把建表一起回滚。)
DO $$
DECLARE
    expected TEXT[] := ARRAY[
        'id','factory_id','individual_code','tag_number','species','breed','sex',
        'birth_date','origin_farm','external_gene_bank_id','dna_sample_code',
        'dna_verification_status','sire_individual_id','dam_individual_id',
        'ext_attributes','notes','version',
        'created_at','updated_at','deleted_at'];
    actual   TEXT[];
    mb_col   INTEGER;
BEGIN
    SELECT array_agg(column_name::text ORDER BY column_name) INTO actual
      FROM information_schema.columns
     WHERE table_schema = 'public' AND table_name = 'source_individuals';
    IF actual IS NULL OR actual <> (SELECT array_agg(x ORDER BY x) FROM unnest(expected) x) THEN
        RAISE EXCEPTION 'V20261101_42: source_individuals 列集合与实体不一致, expected=% actual=%',
            expected, actual;
    END IF;

    -- 硬约束 9: 「没报错」要配一个「必然发生」的读数 —— ADD COLUMN IF NOT EXISTS 在列
    -- 已存在时是静默 no-op, 与「加上了」长得一模一样。这里显式数一次。
    SELECT count(*) INTO mb_col
      FROM information_schema.columns
     WHERE table_schema = 'public' AND table_name = 'material_batches'
       AND column_name = 'source_individual_id';
    IF mb_col <> 1 THEN
        RAISE EXCEPTION 'V20261101_42: material_batches.source_individual_id 没有建出来 (count=%)', mb_col;
    END IF;

    RAISE NOTICE 'V20261101_42: source_individuals 建表完成 % 列; material_batches.source_individual_id 就位',
        array_length(actual, 1);
END $$;
