package com.cretas.aims.repository.bom;

import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipe.Status;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;

/**
 * BomRecipe Repository (Track D1 / M-BOM-1).
 *
 * <p>注意 PostgreSQL parameter-side IS NULL 用 {@code CAST(:param AS string)} hint
 * (per .claude/rules/database-entity-sync.md). 本 repo 暂无 nullable param,
 * 后续若加 filter 需要遵循此规则.
 *
 * @author Cretas Team / Track D1
 * @since 2026-05-14
 */
@Repository
public interface BomRecipeRepository extends JpaRepository<BomRecipe, String> {

    /** 取产品的当前生效 BOM (status=ACTIVE + is_current=TRUE). */
    Optional<BomRecipe> findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
            String factoryId, String productTypeId, Status status);

    Optional<BomRecipe> findFirstByFactoryIdAndProductTypeIdAndWorkflowRevisionIdAndStatusOrderByVersionDesc(
            String factoryId, String productTypeId, Long workflowRevisionId, Status status);

    List<BomRecipe> findByFactoryIdAndProductTypeIdAndWorkflowRevisionIdInOrderByVersionDesc(
            String factoryId, String productTypeId, java.util.Collection<Long> workflowRevisionIds);

    List<BomRecipe> findByFactoryIdAndWorkflowRevisionIdAndStatusOrderByProductTypeIdAsc(
            String factoryId, Long workflowRevisionId, Status status);

    List<BomRecipe> findByFactoryIdAndBomFamilyIdOrderByProductTypeIdAscVersionDesc(
            String factoryId, String bomFamilyId);

    List<BomRecipe> findByFactoryIdAndBomFamilyIdAndStatusOrderByProductTypeIdAsc(
            String factoryId, String bomFamilyId, Status status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select recipe
              from BomRecipe recipe
             where recipe.id = :recipeId
               and recipe.factoryId = :factoryId
            """)
    Optional<BomRecipe> lockByIdAndFactoryId(
            @Param("recipeId") String recipeId,
            @Param("factoryId") String factoryId);

    /**
     * 只取配方的 BOM Family id (标量, 不把配方实体读进持久化上下文), 供
     * {@link com.cretas.aims.service.bom.BomRecipeHeadLocks} 先定位整族再按固定顺序加锁。
     */
    @Query("""
            select recipe.bomFamilyId
              from BomRecipe recipe
             where recipe.id = :recipeId
               and recipe.factoryId = :factoryId
            """)
    Optional<String> findBomFamilyIdByIdAndFactoryId(
            @Param("recipeId") String recipeId,
            @Param("factoryId") String factoryId);

    /**
     * 目标配方及其同族成员的产品 id (标量)。激活 / 整表更新要先按产品 id 升序锁产品行, 再锁整族配方头
     * (锁序见 {@link com.cretas.aims.service.bom.BomRecipeHeadLocks})。
     */
    @Query("""
            select distinct member.productTypeId
              from BomRecipe member
             where member.factoryId = :factoryId
               and (member.id = :recipeId
                    or member.bomFamilyId = (select target.bomFamilyId from BomRecipe target
                                              where target.id = :recipeId and target.factoryId = :factoryId))
            """)
    List<String> findFamilyProductTypeIds(
            @Param("factoryId") String factoryId,
            @Param("recipeId") String recipeId);

    /** 产品的草稿配方 id (标量, 不进持久化上下文) —— ensureDraft 先锁这些草稿的族再读。 */
    @Query("""
            select recipe.id
              from BomRecipe recipe
             where recipe.factoryId = :factoryId
               and recipe.productTypeId = :productTypeId
               and recipe.status = :status
             order by recipe.id asc
            """)
    List<String> findIdsByFactoryIdAndProductTypeIdAndStatus(
            @Param("factoryId") String factoryId,
            @Param("productTypeId") String productTypeId,
            @Param("status") Status status);

    /**
     * 按配方 id 升序锁住同一 BOM Family 的全部配方头 (N1)。改一个成员的行价会重算并改写全族的配方头,
     * 两个成员同时调价若各自先锁自己的头、再去写对方的头就会互等; 全族按同一顺序加锁则不会。
     * PostgreSQL 的 FOR UPDATE 在排序之后逐行加锁, 所以 ORDER BY 就是加锁顺序。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select recipe
              from BomRecipe recipe
             where recipe.factoryId = :factoryId
               and recipe.bomFamilyId = :bomFamilyId
             order by recipe.id asc
            """)
    List<BomRecipe> lockFamilyByFactoryIdAndBomFamilyIdOrderById(
            @Param("factoryId") String factoryId,
            @Param("bomFamilyId") String bomFamilyId);

    /**
     * 按配方 id 升序锁住一组配方头 (只锁这些行)。排序交给 PostgreSQL 的 ORDER BY —— 与
     * {@link #lockFamilyByFactoryIdAndBomFamilyIdOrderById} 用同一个比较规则 (库的 collation),
     * ⛔ 不要在 Java 里先排序再逐个锁: 非 UUID 形状的 id 在两种比较规则下顺序可能相反, 会成环。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select recipe
              from BomRecipe recipe
             where recipe.factoryId = :factoryId
               and recipe.id in :recipeIds
             order by recipe.id asc
            """)
    List<BomRecipe> lockByFactoryIdAndIdInOrderById(
            @Param("factoryId") String factoryId,
            @Param("recipeIds") java.util.Collection<String> recipeIds);

    /** 取产品当前 BOM (is_current=TRUE, 不论 DRAFT/ACTIVE) — picker 软过滤用, 定义即生效无需激活仪式. */
    Optional<BomRecipe> findByFactoryIdAndProductTypeIdAndIsCurrentTrue(
            String factoryId, String productTypeId);

    /** 取产品所有版本 (含 DRAFT/ARCHIVED), 按 version DESC. */
    List<BomRecipe> findByFactoryIdAndProductTypeIdOrderByVersionDesc(
            String factoryId, String productTypeId);

    /** 按状态分页 (工厂级). */
    Page<BomRecipe> findByFactoryIdAndStatus(String factoryId, Status status, Pageable pageable);

    /** 工厂级所有 BOM (含 archived), 默认按 product_type_id 排序. */
    Page<BomRecipe> findByFactoryId(String factoryId, Pageable pageable);

    /**
     * 找同产品其他仍占用生效语义的版本。
     *
     * <p>历史数据可能出现 {@code status=ACTIVE, isCurrent=false}。激活新版本时必须同时
     * 收口 status 与 isCurrent，不能只清 current 标记后留下多个“已生效”版本。
     */
    @Query("SELECT br FROM BomRecipe br " +
           "WHERE br.factoryId = :factoryId " +
           "AND br.productTypeId = :productTypeId " +
           "AND (br.isCurrent = true OR br.status = :activeStatus) " +
           "AND br.id <> :excludeId")
    List<BomRecipe> findCompetingVersionsForActivation(
            @Param("factoryId") String factoryId,
            @Param("productTypeId") String productTypeId,
            @Param("excludeId") String excludeId,
            @Param("activeStatus") Status activeStatus);

    /** 取产品最大 version (clone 时 +1 生成新版本). */
    @Query("SELECT COALESCE(MAX(br.version), 0) FROM BomRecipe br " +
           "WHERE br.factoryId = :factoryId AND br.productTypeId = :productTypeId")
    Integer findMaxVersion(@Param("factoryId") String factoryId,
                           @Param("productTypeId") String productTypeId);

    long countByFactoryIdAndProductTypeId(String factoryId, String productTypeId);

    boolean existsByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
            String factoryId, String productTypeId, Status status);

    /** 当日 recipeCode 序号 (用于 BOM-YYYYMMDD-NNN 自动生成). */
    @Query("SELECT COUNT(br) FROM BomRecipe br " +
           "WHERE br.factoryId = :factoryId " +
           "AND br.recipeCode LIKE :prefix")
    long countByRecipeCodePrefix(@Param("factoryId") String factoryId,
                                  @Param("prefix") String prefix);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE bom_recipes
               SET seasoning_revision = seasoning_revision + 1
             WHERE id = :recipeId
               AND factory_id = :factoryId
               AND status = 'DRAFT'
               AND seasoning_revision = :expectedRevision
               AND deleted_at IS NULL
            """, nativeQuery = true)
    int claimSeasoningRevision(@Param("recipeId") String recipeId,
                               @Param("factoryId") String factoryId,
                               @Param("expectedRevision") Long expectedRevision);

    /**
     * 版本审批同步配方头的「当前版本」两列 —— 定向 UPDATE, 只写这两列。
     * 配方头没有乐观锁 / @DynamicUpdate, 若读实体再 save, 提交时整行 UPDATE 会把并发重算刚提交的成本列
     * 用旧值写回 (红线 3 后续)。不清持久化上下文: 审批事务里别的托管对象还要用。
     * native SQL 绕过实体上的 {@code @Where(deleted_at IS NULL)} 与 {@code @PreUpdate}: 软删条件和
     * updated_at 都要在这里自己写 (已软删的配方不能被重新标成当前版本)。返回改到的行数。
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE bom_recipes
               SET is_current = TRUE,
                   version = :version,
                   updated_at = now()
             WHERE id = :recipeId
               AND factory_id = :factoryId
               AND deleted_at IS NULL
            """, nativeQuery = true)
    int markCurrentVersion(@Param("recipeId") String recipeId,
                           @Param("factoryId") String factoryId,
                           @Param("version") Integer version);

    boolean existsByFactoryIdAndRecipeCode(String factoryId, String recipeCode);
}
