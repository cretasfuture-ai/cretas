package com.cretas.aims.repository;

import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.service.finance.VoucherOwnershipSql;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Collection;
import java.util.Optional;

@Repository
public interface VoucherRepository extends JpaRepository<Voucher, String> {

    Page<Voucher> findByFactoryIdAndDeletedAtIsNull(String factoryId, Pageable pageable);

    Page<Voucher> findByFactoryIdAndStatusAndDeletedAtIsNull(String factoryId, VoucherStatus status, Pageable pageable);

    Page<Voucher> findByFactoryIdAndVoucherTypeAndDeletedAtIsNull(String factoryId, VoucherType type, Pageable pageable);

    /** 同时按 status + type 过滤 (修复 status/type 同传时 type 被静默忽略). */
    Page<Voucher> findByFactoryIdAndStatusAndVoucherTypeAndDeletedAtIsNull(
            String factoryId, VoucherStatus status, VoucherType type, Pageable pageable);

    /**
     * 凭证列表动态筛选 (status / voucherType / sourceBusinessType 均可选, 任意子集组合).
     * 凭证表是只增不减的财务台账 (unbounded), 与 SystemLogRepository.searchLogs 同一 CAST(:param AS text)
     * IS NULL 模式 — 未传的参数条件恒真, 避免为每种筛选组合各写一个 derived-query 方法
     * (原有 status/type 组合已经需要 4 个方法, 加一维 sourceBusinessType 会翻倍到 8 个)。
     */
    @Query(value = "SELECT * FROM vouchers v WHERE v.factory_id = :factoryId AND v.deleted_at IS NULL " +
           "AND (CAST(:status AS text) IS NULL OR v.status = CAST(:status AS text)) " +
           "AND (CAST(:voucherType AS text) IS NULL OR v.voucher_type = CAST(:voucherType AS text)) " +
           "AND (CAST(:sourceBusinessType AS text) IS NULL OR v.source_business_type = CAST(:sourceBusinessType AS text)) " +
           "ORDER BY v.voucher_date DESC, v.created_at DESC",
           countQuery = "SELECT COUNT(*) FROM vouchers v WHERE v.factory_id = :factoryId AND v.deleted_at IS NULL " +
           "AND (CAST(:status AS text) IS NULL OR v.status = CAST(:status AS text)) " +
           "AND (CAST(:voucherType AS text) IS NULL OR v.voucher_type = CAST(:voucherType AS text)) " +
           "AND (CAST(:sourceBusinessType AS text) IS NULL OR v.source_business_type = CAST(:sourceBusinessType AS text))",
           nativeQuery = true)
    Page<Voucher> searchVouchers(@Param("factoryId") String factoryId,
                                  @Param("status") String status,
                                  @Param("voucherType") String voucherType,
                                  @Param("sourceBusinessType") String sourceBusinessType,
                                  Pageable pageable);

    @Query(value = "SELECT * FROM vouchers v WHERE v.factory_id = :factoryId AND v.deleted_at IS NULL " +
           "AND v.id IN (:voucherIds) " +
           "AND (CAST(:status AS text) IS NULL OR v.status = CAST(:status AS text)) " +
           "AND (CAST(:voucherType AS text) IS NULL OR v.voucher_type = CAST(:voucherType AS text)) " +
           "AND (CAST(:sourceBusinessType AS text) IS NULL OR v.source_business_type = CAST(:sourceBusinessType AS text)) " +
           "ORDER BY v.voucher_date DESC, v.created_at DESC",
           countQuery = "SELECT COUNT(*) FROM vouchers v WHERE v.factory_id = :factoryId AND v.deleted_at IS NULL " +
           "AND v.id IN (:voucherIds) " +
           "AND (CAST(:status AS text) IS NULL OR v.status = CAST(:status AS text)) " +
           "AND (CAST(:voucherType AS text) IS NULL OR v.voucher_type = CAST(:voucherType AS text)) " +
           "AND (CAST(:sourceBusinessType AS text) IS NULL OR v.source_business_type = CAST(:sourceBusinessType AS text))",
           nativeQuery = true)
    Page<Voucher> searchVouchersInScope(@Param("factoryId") String factoryId,
                                        @Param("status") String status,
                                        @Param("voucherType") String voucherType,
                                        @Param("sourceBusinessType") String sourceBusinessType,
                                        @Param("voucherIds") Collection<String> voucherIds,
                                        Pageable pageable);

    @Query(value = VoucherOwnershipSql.OWNER_SCOPE_CTE
            + "SELECT v.* FROM vouchers v JOIN scoped_vouchers sv ON sv.id=v.id AND sv.factory_id=v.factory_id "
            + "WHERE v.factory_id=:factoryId AND v.deleted_at IS NULL "
            + "AND (CAST(:status AS text) IS NULL OR v.status=CAST(:status AS text)) "
            + "AND (CAST(:voucherType AS text) IS NULL OR v.voucher_type=CAST(:voucherType AS text)) "
            + "AND (CAST(:sourceBusinessType AS text) IS NULL OR v.source_business_type=CAST(:sourceBusinessType AS text)) "
            + "ORDER BY v.voucher_date DESC, v.created_at DESC",
           countQuery = VoucherOwnershipSql.OWNER_SCOPE_CTE
            + "SELECT COUNT(*) FROM vouchers v JOIN scoped_vouchers sv ON sv.id=v.id AND sv.factory_id=v.factory_id "
            + "WHERE v.factory_id=:factoryId AND v.deleted_at IS NULL "
            + "AND (CAST(:status AS text) IS NULL OR v.status=CAST(:status AS text)) "
            + "AND (CAST(:voucherType AS text) IS NULL OR v.voucher_type=CAST(:voucherType AS text)) "
            + "AND (CAST(:sourceBusinessType AS text) IS NULL OR v.source_business_type=CAST(:sourceBusinessType AS text))",
           nativeQuery = true)
    Page<Voucher> searchVouchersInOwnerScope(@Param("factoryId") String factoryId,
                                              @Param("status") String status,
                                              @Param("voucherType") String voucherType,
                                              @Param("sourceBusinessType") String sourceBusinessType,
                                              @Param("ownerIdsJson") String ownerIdsJson,
                                              Pageable pageable);

    @Query(value = VoucherOwnershipSql.OWNER_SCOPE_CTE
            + "SELECT v.* FROM vouchers v JOIN scoped_vouchers sv ON sv.id=v.id AND sv.factory_id=v.factory_id "
            + "WHERE v.factory_id=:factoryId AND v.voucher_date BETWEEN :from AND :to AND v.deleted_at IS NULL",
           nativeQuery = true)
    List<Voucher> findByFactoryIdAndOwnerScopeAndVoucherDateBetweenAndDeletedAtIsNull(
            @Param("factoryId") String factoryId, @Param("from") LocalDate from,
            @Param("to") LocalDate to, @Param("ownerIdsJson") String ownerIdsJson);

    @Query(value = VoucherOwnershipSql.OWNER_SCOPE_CTE
            + "SELECT COUNT(*) FROM vouchers v JOIN scoped_vouchers sv ON sv.id=v.id AND sv.factory_id=v.factory_id "
            + "WHERE v.factory_id=:factoryId AND v.status=:status AND v.voucher_date BETWEEN :from AND :to AND v.deleted_at IS NULL",
           nativeQuery = true)
    long countByFactoryIdAndOwnerScopeAndStatusAndVoucherDateBetweenAndDeletedAtIsNull(
            @Param("factoryId") String factoryId, @Param("ownerIdsJson") String ownerIdsJson,
            @Param("status") String status, @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Factory-scoped lookup — enforces tenant isolation. */
    Optional<Voucher> findByIdAndFactoryIdAndDeletedAtIsNull(String id, String factoryId);

    /**
     * Idempotent lookup: 同一业务单 ({businessType, businessId}) 只能有一张凭证.
     * VoucherService.createFromBusiness 先调此方法; 非 null 直接返回 existing,
     * 防止 event 重发导致重复生成.
     */
    Optional<Voucher> findBySourceBusinessTypeAndSourceBusinessIdAndDeletedAtIsNull(
            String sourceBusinessType, String sourceBusinessId);

    /** factory + voucher_number 唯一. */
    Optional<Voucher> findByFactoryIdAndVoucherNumberAndDeletedAtIsNull(String factoryId, String voucherNumber);

    @Query("SELECT v FROM Voucher v WHERE v.factoryId = ?1 AND v.voucherDate BETWEEN ?2 AND ?3 AND v.deletedAt IS NULL")
    List<Voucher> findByFactoryIdAndDateRange(String factoryId, LocalDate from, LocalDate to);

    List<Voucher> findByFactoryIdAndIdInAndVoucherDateBetweenAndDeletedAtIsNull(
            String factoryId, Collection<String> voucherIds, LocalDate from, LocalDate to);

    /**
     * F006 财务审计 Bug 6 (2026-07-04): 统计期间内待过账 (DRAFT) 凭证数 — 官方报表切到
     * POSTED-only 口径后, 用于给财务人员一个"为什么报表数字看着少"的可见提示
     * (pendingDraftVoucherCount), 而不是让报表悄悄变空/变小却不说明原因。
     */
    long countByFactoryIdAndStatusAndVoucherDateBetweenAndDeletedAtIsNull(
            String factoryId, VoucherStatus status, LocalDate from, LocalDate to);

    long countByFactoryIdAndIdInAndStatusAndVoucherDateBetweenAndDeletedAtIsNull(
            String factoryId, Collection<String> voucherIds, VoucherStatus status, LocalDate from, LocalDate to);

    long countByFactoryIdAndStatusAndDeletedAtIsNull(String factoryId, VoucherStatus status);

    /**
     * 凭证号生成: factory + year <b>已占用的最大序号</b>。
     *
     * <p>🔴 2026-08-03 修复: 这里原来是 {@code COUNT(v) ... AND v.deletedAt IS NULL} ——
     * 注释写的是「最大序号」, 代码数的却是「未删条数」。两者一旦分叉, 生成的号就会撞上
     * 已占用的号段, 而且<b>一撞就是永久撞</b>(每次都算出同一个号)。
     *
     * <p>prod 实证 (F006): 2026 年凭证<b>总条数 302 / 未删仅 66 / 最大号 V-2026-0302</b>。
     * 按未删条数生成 → {@code V-2026-0067}, 那个号早被占了 → 期初建账固定报
     * {@code 409 数据已存在}, {@code hintTarget: factory_id, voucher_number} ——
     * 而凭证号是服务端生成的, 调用方根本给不了, 于是这条报错指不到任何可操作的地方。
     *
     * <p>⚠️ 此前被记成「同一工厂当天只能期初建账一次」的限制<b>是误判</b>: 不是每日额度,
     * 是该工厂的凭证号生成已经永久失效(软删了 236 条, 计数与号段脱节)。
     *
     * <p>改成取<b>已占用最大序号</b>, 且<b>不过滤 deletedAt</b> —— 号段一旦发出就不该被回收,
     * 软删的凭证仍占着它的号(审计可追溯)。
     *
     * <p>🔴 2026-08-19 二次修复 —— <b>上面那次修复从未生效</b>。
     * 上一版是 JPQL, 而 {@link com.cretas.aims.entity.finance.Voucher} 上有类级
     * {@code @Where(clause = "deleted_at IS NULL")}。Hibernate 把它拼进<b>每一条</b>
     * 作用于该实体的 HQL/JPQL, 包括这一条 —— 于是 MAX 仍然只看未删的行,
     * 注释里那句「不过滤 deletedAt」<b>是一句没兑现的承诺</b>。
     *
     * <p>prod 实证 (2026-08-19, F006 2026 年):
     * <pre>
     *   V-2026-0001 .. 0013  DRAFT   活着      → MAX(未删) = 13 → 生成器发 V-2026-0014
     *   V-2026-0014 .. 0017  POSTED  已软删    → 0014 正好是第一个软删的号 ⇒ 必撞
     * </pre>
     * 日志里 84 次撞 {@code V-2026-0014}; 更早号段更长的时代 (302 条 / 未删 66)
     * 是 350 次撞 {@code V-2026-0067} —— <b>两个时代都精确等于「未删条数 + 1」</b>,
     * 这就是「JPQL 被 {@code @Where} 过滤」的直接读数。
     *
     * <p>⚠️ 必须是 {@code nativeQuery = true}: {@code @Where} 由 Hibernate 的
     * entity persister 在 HQL/Criteria 层拼装, <b>原生 SQL 不经过它</b>。
     * ⛔ 不要改成把 {@code uk_voucher_factory_number} 变成部分索引 —— 号段一旦发出
     * 就不该回收, 软删的凭证<b>应该</b>继续占着它的号。
     * ⛔ 也不要写成 JPQL 加 {@code OR v.deletedAt IS NOT NULL}: {@code @Where} 是
     * {@code AND} 进去的, 加什么 {@code OR} 都绕不开。
     */
    /*
     * 🔴 2026-08-25 prod 实测: 应用盘点差异时 500「数据服务暂时不可用 (追踪码 7E651E80)」,
     *    整个过账事务回滚。服务器日志根因:
     *      ERROR: could not determine data type of parameter $2
     *
     *    `CONCAT('V-', ?2, '-')` 里的参数**没有类型上下文** —— PostgreSQL 在 Parse 阶段
     *    推不出它是什么类型。pgjdbc 默认 prepareThreshold=5: 前几次走 simple 模式(驱动
     *    带着值和类型一起发), 到第 5 次升级成服务端 prepared statement, Parse 时没有值
     *    可推 ⇒ 从那一刻起该连接上**每次都炸**。
     *    这解释了「库里有 17 张凭证(最后一张 8-18), 但今天必失败」这个看起来矛盾的读数。
     *
     *    ⇒ 显式 CAST 给参数定类型。已在 prod 库上用 PREPARE 双向验证:
     *       现状式 → could not determine data type of parameter $2
     *       CAST 式 → PREPARE 成功, 返回 17, 而真实 max 就是 V-2026-0017 (未删仅 13)。
     *    ⛔ 返回 17 而不是 13 —— 上面那条软删占号的修复没有被这次改动带回去。
     */
    @Query(value = "SELECT COALESCE(MAX(CAST(SUBSTRING(v.voucher_number, LENGTH(CONCAT('V-', CAST(?2 AS text), '-')) + 1) AS integer)), 0) "
            + "FROM vouchers v WHERE v.factory_id = CAST(?1 AS text) AND v.voucher_number LIKE CONCAT('V-', CAST(?2 AS text), '-%')",
            nativeQuery = true)
    long maxVoucherSequenceByFactoryIdAndYear(String factoryId, String year);

    /** 结转损益: 某期 active 结转凭证 (POSTED + 非红冲镜像), 反结账时红冲它们。?2 = sourceBusinessId LIKE 前缀。 */
    @Query("SELECT v FROM Voucher v WHERE v.factoryId = ?1 " +
            "AND v.sourceBusinessType = 'PL_CLOSING' " +
            "AND v.sourceBusinessId LIKE ?2 " +
            "AND v.status = com.cretas.aims.entity.enums.VoucherStatus.POSTED " +
            "AND v.originalVoucherId IS NULL " +
            "AND v.deletedAt IS NULL")
    List<Voucher> findActiveClosingVouchers(String factoryId, String sourceIdPrefix);

    /** 结转损益: 某期历史结转批次数 (含 REVERSED, 排红冲镜像) — 算 revision。?2 = sourceBusinessId LIKE 前缀。 */
    @Query("SELECT COUNT(v) FROM Voucher v WHERE v.factoryId = ?1 " +
            "AND v.sourceBusinessType = 'PL_CLOSING' " +
            "AND v.sourceBusinessId LIKE ?2 " +
            "AND v.originalVoucherId IS NULL " +
            "AND v.deletedAt IS NULL")
    long countClosingBatches(String factoryId, String sourceIdPrefix);

    /** 结转成本: 某期 active 结转成本凭证 (POSTED + 非红冲镜像), 反结账时红冲它们。?2 = sourceBusinessId LIKE 前缀。 */
    @Query("SELECT v FROM Voucher v WHERE v.factoryId = ?1 " +
            "AND v.sourceBusinessType = 'COST_CARRYOVER' " +
            "AND v.sourceBusinessId LIKE ?2 " +
            "AND v.status = com.cretas.aims.entity.enums.VoucherStatus.POSTED " +
            "AND v.originalVoucherId IS NULL " +
            "AND v.deletedAt IS NULL")
    List<Voucher> findActiveCostCarryoverVouchers(String factoryId, String sourceIdPrefix);

    /** 结转成本: 某期历史结转批次数 (含 REVERSED, 排红冲镜像) — 算 revision。?2 = sourceBusinessId LIKE 前缀。 */
    @Query("SELECT COUNT(v) FROM Voucher v WHERE v.factoryId = ?1 " +
            "AND v.sourceBusinessType = 'COST_CARRYOVER' " +
            "AND v.sourceBusinessId LIKE ?2 " +
            "AND v.originalVoucherId IS NULL " +
            "AND v.deletedAt IS NULL")
    long countCostCarryoverBatches(String factoryId, String sourceIdPrefix);
}
