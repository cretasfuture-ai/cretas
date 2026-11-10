package com.cretas.aims.repository;

import com.cretas.aims.entity.FactorySettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Optional;
/**
 * 工厂设置数据访问接口
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Repository
public interface FactorySettingsRepository extends JpaRepository<FactorySettings, Integer> {
    /**
     * 根据工厂ID查找设置
     */
    Optional<FactorySettings> findByFactoryId(String factoryId);
     /**
     * 检查工厂设置是否存在
      */
    boolean existsByFactoryId(String factoryId);
     /**
     * 获取AI设置
      */
    @Query("SELECT f.aiSettings FROM FactorySettings f WHERE f.factoryId = :factoryId")
    String findAiSettingsByFactoryId(@Param("factoryId") String factoryId);
     /**
     * 获取通知设置
      */
    @Query("SELECT f.notificationSettings FROM FactorySettings f WHERE f.factoryId = :factoryId")
    String findNotificationSettingsByFactoryId(@Param("factoryId") String factoryId);
     /**
     * 获取生产设置
      */
    @Query("SELECT f.productionSettings FROM FactorySettings f WHERE f.factoryId = :factoryId")
    String findProductionSettingsByFactoryId(@Param("factoryId") String factoryId);
     /**
     * 获取库存设置
      */
    @Query("SELECT f.inventorySettings FROM FactorySettings f WHERE f.factoryId = :factoryId")
    String findInventorySettingsByFactoryId(@Param("factoryId") String factoryId);
     /**
     * 获取工作时间设置
      */
    @Query("SELECT f.workTimeSettings FROM FactorySettings f WHERE f.factoryId = :factoryId")
    String findWorkTimeSettingsByFactoryId(@Param("factoryId") String factoryId);
     /**
     * 获取工厂级"免工序报工默认值" (Fable 审计修复 — 多租户安全, V20261018_02).
     * 无 settings 行时返回 null (调用方兜底为 false = 逐道, 安全默认).
      */
    @Query("SELECT f.skipProcessReportingDefault FROM FactorySettings f WHERE f.factoryId = :factoryId")
    Boolean findSkipProcessReportingDefaultByFactoryId(@Param("factoryId") String factoryId);
     /**
     * 获取工厂级"报工前必须领料确认"开关 (② Part B 生产领料单 Gate, V20261027_32).
     * 无 settings 行时返回 null (调用方兜底为 false = 报工照旧, 向后兼容安全默认).
      */
    @Query("SELECT f.requireRequisitionBeforeReport FROM FactorySettings f WHERE f.factoryId = :factoryId")
    Boolean findRequireRequisitionBeforeReportByFactoryId(@Param("factoryId") String factoryId);

    /**
     * 工厂级「缺料时拦开工」开关 (S4-08, V20261103_136)。无 settings 行时返回 null —— 调用方按 false 处理
     * (开工照旧, 即六膳门 N1)。
     */
    @Query("SELECT f.blockStartOnOpenShortage FROM FactorySettings f WHERE f.factoryId = :factoryId")
    Boolean findBlockStartOnOpenShortageByFactoryId(@Param("factoryId") String factoryId);

    /**
     * 工厂级「允许本人审批」开关 (V20261110_47)。无 settings 行时返回 null —— 调用方按 false 处理 (四眼照旧)。
     */
    @Query("SELECT f.allowSelfApproval FROM FactorySettings f WHERE f.factoryId = :factoryId")
    Boolean findAllowSelfApprovalByFactoryId(@Param("factoryId") String factoryId);

    /** 改价审批阈值 [降价%, 涨价%] (V20261110_47)。无 settings 行 → 空, 调用方用默认 10/20。 */
    @Query("SELECT f.priceDecreaseApprovalPct, f.priceIncreaseApprovalPct FROM FactorySettings f WHERE f.factoryId = :factoryId")
    java.util.List<Object[]> findPriceAdjustmentApprovalThresholdsByFactoryId(@Param("factoryId") String factoryId);
    /** 物料平衡阈值 [warn%, red%] (V20261103_140)。无 settings 行 → 空, 调用方用默认 5/15。 */
    @Query("SELECT f.materialBalanceWarnPct, f.materialBalanceRedPct FROM FactorySettings f WHERE f.factoryId = :factoryId")
    java.util.List<Object[]> findMaterialBalanceThresholdsByFactoryId(@Param("factoryId") String factoryId);

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE FactorySettings f SET f.materialBalanceWarnPct = :warn, f.materialBalanceRedPct = :red WHERE f.factoryId = :factoryId")
    int updateMaterialBalanceThresholds(@Param("factoryId") String factoryId, @Param("warn") java.math.BigDecimal warn,
                                        @Param("red") java.math.BigDecimal red);

    /** 成本 / 损耗告警阈值 [成本差异%, 物料平衡%] (V20261104_31), 各自可空 = 未配置。 */
    @Query("SELECT f.costDeviationVariancePct, f.costDeviationBalancePct FROM FactorySettings f WHERE f.factoryId = :factoryId")
    java.util.List<Object[]> findCostDeviationThresholdsByFactoryId(@Param("factoryId") String factoryId);

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE FactorySettings f SET f.costDeviationVariancePct = :variance, f.costDeviationBalancePct = :balance WHERE f.factoryId = :factoryId")
    int updateCostDeviationThresholds(@Param("factoryId") String factoryId, @Param("variance") java.math.BigDecimal variance,
                                      @Param("balance") java.math.BigDecimal balance);

    /** 配了任一告警阈值的工厂; 定时扫描只扫它们。 */
    @Query("SELECT f.factoryId FROM FactorySettings f WHERE f.costDeviationVariancePct IS NOT NULL OR f.costDeviationBalancePct IS NOT NULL")
    java.util.List<String> findFactoryIdsWithCostDeviationThresholds();

     /**
     * 更新AI每周配额（仅平台管理员可用）
      */
    @Query("UPDATE FactorySettings f SET f.aiWeeklyQuota = :quota WHERE f.factoryId = :factoryId")
    void updateAiWeeklyQuota(@Param("factoryId") String factoryId, @Param("quota") Integer quota);
}
