package com.cretas.aims.repository.material;

import com.cretas.aims.entity.material.MaterialBatchDateCorrection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 批次日期更正台账的数据访问层。
 *
 * <p>只有插入与按批次/工厂读两种用法 —— 审计行不改不删。
 */
@Repository
public interface MaterialBatchDateCorrectionRepository
        extends JpaRepository<MaterialBatchDateCorrection, String> {

    /** 「这个批次的日期被改过几次、谁改的、为什么」。 */
    List<MaterialBatchDateCorrection> findByMaterialBatchIdOrderByCorrectedAtDesc(String materialBatchId);

    /** 按工厂审计。 */
    List<MaterialBatchDateCorrection> findByFactoryIdOrderByCorrectedAtDesc(String factoryId);
}
