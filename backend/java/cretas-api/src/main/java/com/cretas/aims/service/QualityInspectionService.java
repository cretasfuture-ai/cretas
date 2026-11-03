package com.cretas.aims.service;

import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.QualityInspection;

/**
 * 质量检验服务接口
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-19
 */
public interface QualityInspectionService {

    /**
     * 分页查询质量检验记录
     *
     * @param factoryId 工厂ID
     * @param productionBatchId 生产批次ID（可选）
     * @param pageRequest 分页请求
     * @return 分页质量检验记录
     */
    PageResponse<QualityInspection> getInspections(String factoryId, String productionBatchId, PageRequest pageRequest);

    /**
     * 按批次 / 检验结果 / 检验日期 / 质检类型标记筛选 (每个可选, null = 不限), 在分页之前由数据库过滤,
     * 总数是筛过的。AI 工具 quality_check_query 走这里。
     */
    PageResponse<QualityInspection> getInspectionsFiltered(String factoryId, String productionBatchId, String result,
                                                           java.time.LocalDate inspectionDateFrom,
                                                           java.time.LocalDate inspectionDateTo,
                                                           String checkTypePattern, PageRequest pageRequest);

    /**
     * 根据ID获取质量检验记录详情
     *
     * @param factoryId 工厂ID
     * @param inspectionId 检验记录ID
     * @return 质量检验记录
     */
    QualityInspection getInspectionById(String factoryId, String inspectionId);

    /**
     * 创建质量检验记录
     *
     * @param factoryId 工厂ID
     * @param inspection 质量检验记录
     * @return 创建的质量检验记录
     */
    QualityInspection createInspection(String factoryId, QualityInspection inspection);

    /**
     * 更新质量检验记录
     *
     * @param factoryId 工厂ID
     * @param inspectionId 检验记录ID
     * @param inspection 质量检验记录
     * @return 更新后的质量检验记录
     */
    QualityInspection updateInspection(String factoryId, String inspectionId, QualityInspection inspection);
}
