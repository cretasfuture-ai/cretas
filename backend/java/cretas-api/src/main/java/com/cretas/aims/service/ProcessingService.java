package com.cretas.aims.service;

import com.cretas.aims.dto.batch.TeamBatchReportRequest;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
/**
 * 生产加工服务接口
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
public interface ProcessingService {
    // ========== 批次管理 ==========
    /**
     * 创建生产批次
     */
    ProductionBatch createBatch(String factoryId, ProductionBatch batch);
     /**
     * 开始生产
      */
    ProductionBatch startProduction(String factoryId, String batchId, Integer supervisorId);
     /**
     * 暂停生产
      */
    ProductionBatch pauseProduction(String factoryId, String batchId, String reason);
     /**
     * 恢复生产
      */
    ProductionBatch resumeProduction(String factoryId, String batchId);
     /**
     * 完成生产
      */
    ProductionBatch completeProduction(String factoryId, String batchId, BigDecimal actualQuantity,
                                       BigDecimal goodQuantity, BigDecimal defectQuantity);

    /**
     * 完成生产 (P0-2 重载): finishedUnit 非空时按"末道产出单位"(如 份/盒)入成品库,
     * 而非批次原单位 (kg)。finishedUnit=null 行为同上重载 (沿用 batch.unit)。
     */
    ProductionBatch completeProduction(String factoryId, String batchId, BigDecimal actualQuantity,
                                       BigDecimal goodQuantity, BigDecimal defectQuantity, String finishedUnit);
     /**
     * 取消生产
      */
    ProductionBatch cancelProduction(String factoryId, String batchId, String reason);
     /**
     * 获取批次详情
      */
    ProductionBatch getBatchById(String factoryId, String batchId);

    /**
     * Resolve a persisted production batch by its exact batch number within a factory.
     * This is a read-only lookup used by quality inspection scan/manual entry.
     */
    ProductionBatch getBatchByNumber(String factoryId, String batchNumber);
     /**
     * 获取批次列表
      */
    PageResponse<ProductionBatch> getBatches(String factoryId, String status, PageRequest pageRequest);

    /** Quality task queue backed by persisted PENDING inspection observations. */
    PageResponse<ProductionBatch> getQualityPendingBatches(String factoryId, PageRequest pageRequest);

    /**
     * 获取批次列表（带主管过滤）
     */
    PageResponse<ProductionBatch> getBatches(String factoryId, String status, Long supervisorId, PageRequest pageRequest);

    /**
     * 获取批次列表（带主管 + 试制标记过滤, SP10 中报价试制批次下拉）
     */
    PageResponse<ProductionBatch> getBatches(String factoryId, String status, Long supervisorId, Boolean isTrial, PageRequest pageRequest);

    PageResponse<ProductionBatch> getBatches(String factoryId, String status, Long supervisorId,
                                             Boolean isTrial, Boolean includeClerkWip,
                                             PageRequest pageRequest);

    /**
     * 获取批次列表(带批次号搜索, 缺陷 50 第二步 —— server-side, 分页在过滤之后)。
     *
     * <p>{@code batchNumber} 为空/null 时与上面 6 参版本行为完全一致(委托给同一条既有代码路径,
     * 零改动)。非空时按批次号模糊/不区分大小写匹配, 命中 CLERK_WIP 合成行时同时按其
     * 归属计划号(ProcessSheetRow.planId → ProductionPlan.planNumber)匹配 —— 该字段在
     * ProductionBatch 上是 @Transient, 不能直接 WHERE, 见 ProductionBatchRepository
     * #searchByFactoryIdAndBatchNumber 的实现注释。
     */
    PageResponse<ProductionBatch> getBatches(String factoryId, String status, Long supervisorId,
                                             Boolean isTrial, Boolean includeClerkWip, String batchNumber,
                                             PageRequest pageRequest);

    /**
     * 班组批量报工
     */
    Map<String, Object> submitTeamBatchReport(String factoryId, TeamBatchReportRequest request);

     /**
     * 获取批次时间线
      */
    List<Map<String, Object>> getBatchTimeline(String factoryId, String batchId);
    // ========== 原材料管理 ==========
     /**
     * 获取原材料列表
      */
    PageResponse<MaterialBatch> getMaterialReceipts(String factoryId, PageRequest pageRequest);
     /**
     * 更新原材料信息
      */
    MaterialBatch updateMaterialReceipt(String factoryId, String batchId, MaterialBatch updates);
     /**
     * 原材料消耗记录 (向后兼容)
      */
    void recordMaterialConsumption(String factoryId, String productionBatchId,
                                   List<Map<String, Object>> consumptions);

    /**
     * 原材料消耗记录 (带用户ID)
     */
    void recordMaterialConsumption(String factoryId, String productionBatchId,
                                   List<Map<String, Object>> consumptions, Long userId);
    // ========== 质量检验 ==========
     /**
     * 提交质检记录
      */
    Map<String, Object> submitInspection(String factoryId, String batchId, Map<String, Object> inspection);
    Map<String, Object> getDynamicQualityForm(String factoryId, String batchId);
    Map<String, Object> submitDynamicInspection(String factoryId, String batchId,
                                                 com.cretas.aims.dto.quality.DynamicQualityInspectionRequest request,
                                                 Long inspectorId);
     /**
     * 获取质检记录
      */
    PageResponse<Map<String, Object>> getInspections(String factoryId, String batchId, PageRequest pageRequest);

    /**
     * 同上, 额外按检测结果过滤。{@code results} 为 null / 空表示不过滤。
     */
    PageResponse<Map<String, Object>> getInspections(
            String factoryId, String batchId, java.util.List<String> results, PageRequest pageRequest);
     /**
     * 获取质检详情
      */
    Map<String, Object> getInspectionById(String factoryId, String inspectionId);
     /**
     * 获取质检统计
      */
    Map<String, Object> getQualityStatistics(String factoryId, LocalDate startDate, LocalDate endDate);
     /**
     * 获取质量趋势
      */
    List<Map<String, Object>> getQualityTrends(String factoryId, Integer days);
    // ========== 设备监控 ==========
     /**
     * 记录设备使用
      */
    void recordEquipmentUsage(String factoryId, String batchId, Long equipmentId,
                             LocalDate startTime, LocalDate endTime);
     /**
     * 获取设备监控数据
      */
    List<Map<String, Object>> getEquipmentMonitoring(String factoryId);
     /**
     * 获取设备指标
      */
    Map<String, Object> getEquipmentMetrics(String factoryId, Long equipmentId, Integer days);
     /**
     * 记录设备维护
      */
    void recordEquipmentMaintenance(String factoryId, Long equipmentId, Map<String, Object> maintenance);
    // ========== 成本分析 ==========
     /**
     * 获取批次成本分析
      */
    Map<String, Object> getBatchCostAnalysis(String factoryId, String batchId);
     /**
     * 重新计算批次成本
      */
    ProductionBatch recalculateBatchCost(String factoryId, String batchId);

    /**
     * 获取增强版批次成本分析（包含完整costBreakdown结构）
     * 用于前端CostAnalysisDashboard
     */
    Map<String, Object> getEnhancedBatchCostAnalysis(String factoryId, String batchId);

     /**
     * AI成本分析建议（旧版本，基于规则）
      */
    Map<String, Object> getAICostAnalysis(String factoryId, String batchId);

    /**
     * AI智能成本分析（新版本，调用AI服务）
     *
     * @param factoryId 工厂ID
     * @param batchId 批次ID
     * @param sessionId 会话ID（可选，用于多轮对话）
     * @param customMessage 自定义问题（可选）
     * @return AI分析结果
     */
    Map<String, Object> analyzeWithAI(String factoryId, String batchId,
                                      String sessionId, String customMessage);

    /**
     * AI服务健康检查
     *
     * @return 健康状态
     */
    Map<String, Object> checkAIServiceHealth();

    // ========== 时间范围分析 ==========

    /**
     * 获取指定时间范围内的批次成本摘要数据（用于周报告/月报告）
     * 返回轻量级的批次成本摘要，不包含详细的业务链数据
     *
     * @param factoryId 工厂ID
     * @param startDate 开始时间
     * @param endDate 结束时间
     * @return 批次成本摘要数据列表（包含基本信息、成本、指标等）
     */
    List<Map<String, Object>> getWeeklyBatchesCost(
            String factoryId,
            java.time.LocalDateTime startDate,
            java.time.LocalDateTime endDate);

    /**
     * 获取时间范围内的批次成本分析数据
     *
     * @param factoryId 工厂ID
     * @param startDate 开始时间
     * @param endDate 结束时间
     * @return 批次成本数据列表
     */
    List<Map<String, Object>> getTimeRangeBatchesCostAnalysis(
            String factoryId,
            java.time.LocalDateTime startDate,
            java.time.LocalDateTime endDate);

    // ========== 批次对比分析 ==========

    /**
     * 获取多个批次的对比分析数据
     *
     * @param factoryId 工厂ID
     * @param batchIds 批次ID列表（2-5个）
     * @return 批次对比数据列表
     */
    List<Map<String, Object>> getComparativeBatchesCostAnalysis(
            String factoryId,
            List<String> batchIds);

    // ========== 批次员工分配 ==========

    /**
     * 分配员工到批次
     *
     * @param factoryId 工厂ID
     * @param batchId 批次ID
     * @param workerIds 员工ID列表
     * @param assignedBy 分配人ID
     * @param notes 备注
     * @return 分配结果
     */
    List<Map<String, Object>> assignWorkersToBatch(String factoryId, Long batchId,
                                                    List<Long> workerIds, Long assignedBy, String notes);

    /**
     * 员工完成批次工作（签出）
     *
     * @param factoryId 工厂ID
     * @param batchId 批次ID
     * @param workerId 员工ID
     * @param workMinutes 工作分钟数（可选）
     * @param notes 备注
     * @return 签出结果
     */
    Map<String, Object> workerCheckout(String factoryId, Long batchId, Long workerId,
                                        Integer workMinutes, String notes);

    /**
     * 获取批次的员工列表
     *
     * @param factoryId 工厂ID
     * @param batchId 批次ID
     * @return 员工列表
     */
    List<Map<String, Object>> getBatchWorkers(String factoryId, Long batchId);

    /**
     * 取消员工批次分配
     *
     * @param factoryId 工厂ID
     * @param batchId 批次ID
     * @param workerId 员工ID
     * @return 取消结果
     */
    Map<String, Object> cancelWorkerAssignment(String factoryId, Long batchId, Long workerId);

    // ========== 仪表盘 ==========
     /**
     * 生产概览
      */
    Map<String, Object> getDashboardOverview(String factoryId);
     /**
     * 生产统计
      */
    Map<String, Object> getProductionStatistics(String factoryId, String period);
     /**
     * 质量仪表盘
      */
    Map<String, Object> getQualityDashboard(String factoryId);
     /**
     * 设备仪表盘
      */
    Map<String, Object> getEquipmentDashboard(String factoryId);
     /**
     * 告警仪表盘
      */
    Map<String, Object> getAlertsDashboard(String factoryId);
     /**
     * 趋势分析
      */
    Map<String, Object> getTrendAnalysis(String factoryId, String metric, Integer days);
}
