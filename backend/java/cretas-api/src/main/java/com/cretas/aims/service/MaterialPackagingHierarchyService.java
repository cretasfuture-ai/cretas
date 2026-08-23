package com.cretas.aims.service;

import com.cretas.aims.dto.material.MaterialPackagingHierarchyDTO;

import java.util.List;
import java.util.Optional;

/**
 * 原料采购包装换算 Service（动态规则优先，旧层级字段兼容）.
 *
 * @since 2026-05-06
 */
public interface MaterialPackagingHierarchyService {

    /** 列出工厂全部包装换算配置. */
    List<MaterialPackagingHierarchyDTO> listByFactory(String factoryId);

    /** 按原料 ID 获取包装换算配置 (无则空). */
    Optional<MaterialPackagingHierarchyDTO> getByMaterialTypeId(String factoryId, String materialTypeId);

    /**
     * 同上, 但按给定的供应关系把「这条包装规格对应的采购价」一并推导出来。
     *
     * <p>采购单表单在供应商没有配采购规格时会回落到物料侧包装换算, 而那条路径原来一个价格字段
     * 都没有 ⇒ 前端拿不到价就按 MISSING 清空 (客户 2026-07-30 反馈的后半截)。
     * {@code supplierMaterialId} 为空时行为与 {@link #getByMaterialTypeId} 完全一致。
     */
    Optional<MaterialPackagingHierarchyDTO> getByMaterialTypeId(String factoryId, String materialTypeId,
                                                                String supplierMaterialId);

    /** Upsert: 一个原料一条记录, 已存在则更新. */
    MaterialPackagingHierarchyDTO upsert(String factoryId, String materialTypeId,
                                         MaterialPackagingHierarchyDTO dto, Long createdBy);

    /** 软删除 (BaseEntity @SQLDelete). */
    void deleteByMaterialTypeId(String factoryId, String materialTypeId);
}
