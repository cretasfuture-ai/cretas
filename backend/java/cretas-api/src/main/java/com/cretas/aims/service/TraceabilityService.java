package com.cretas.aims.service;

import com.cretas.aims.dto.traceability.TraceabilityDTO;

/**
 * 溯源服务接口
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
public interface TraceabilityService {

    /**
     * 获取基础溯源信息（批次级别）
     *
     * @param factoryId 工厂ID
     * @param batchNumber 批次号
     * @return 基础溯源响应
     */
    TraceabilityDTO.BatchTraceResponse getBatchTrace(String factoryId, String batchNumber);

    /**
     * 获取完整溯源链路
     *
     * @param factoryId 工厂ID
     * @param batchNumber 批次号
     * @return 完整溯源链路响应
     */
    TraceabilityDTO.FullTraceResponse getFullTrace(String factoryId, String batchNumber);

    /**
     * 公开溯源查询（消费者扫码，无需认证）
     *
     * @param batchNumber 批次号
     * @return 公开溯源信息（脱敏）
     */
    TraceabilityDTO.PublicTraceResponse getPublicTrace(String batchNumber);

    /**
     * 召回影响面 —— 从一个批次沿血缘往下游追，列出被波及的生产批次 / 成品批次 / 出货单。
     *
     * <p>⛔ 查不到血缘引擎或批次类型非法时<b>抛异常</b>，不返回一个「全 0」的空壳：
     * 「没有波及」和「没查成」在召回场景里是天差地别的两件事。
     *
     * @param batchType MATERIAL_BATCH / PRODUCTION_BATCH / FINISHED_BATCH / SHIPMENT_RECORD
     * @param maxDepth  最大跳数（&lt;=0 取默认 5，上限 10）
     */
    TraceabilityDTO.ImpactResponse getImpact(String factoryId, String batchType, String batchId, int maxDepth);
}
