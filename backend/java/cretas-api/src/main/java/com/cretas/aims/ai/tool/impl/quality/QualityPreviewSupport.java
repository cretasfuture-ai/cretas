package com.cretas.aims.ai.tool.impl.quality;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.repository.ProductionBatchRepository;

import java.util.Optional;

import static com.cretas.aims.ai.tool.WritePreviewSupport.EMPTY;
import static com.cretas.aims.ai.tool.WritePreviewSupport.notBlank;

/**
 * 质检类写工具预览共用的「人话名字」: 批次号（产品名）、质检记录标题、质检结果中文名。
 * ⛔ 只读 —— 只调 repository 的 find 方法。内部 id 一律不进确认卡。
 */
final class QualityPreviewSupport {

    private QualityPreviewSupport() {
    }

    /** 「批次号（产品名）」; 批次读不到时不回显 id, 写「这个生产批次」。 */
    static String batchTitle(ProductionBatchRepository repo, String factoryId, Long productionBatchId) {
        if (repo == null || productionBatchId == null) {
            return "这个生产批次";
        }
        Optional<ProductionBatch> batch = repo.findByIdAndFactoryId(productionBatchId, factoryId);
        return batch.map(QualityPreviewSupport::batchTitle).orElse("这个生产批次");
    }

    static String batchTitle(ProductionBatch batch) {
        String number = notBlank(batch.getBatchNumber()) ? batch.getBatchNumber() : "生产批次";
        return notBlank(batch.getProductName()) ? number + "（" + batch.getProductName() + "）" : number;
    }

    /** 质检记录的中文标题: 「2026-10-06 的质检（批次 B…）」。 */
    static String inspectionTitle(QualityInspection inspection, String batchTitle) {
        String date = inspection.getInspectionDate() != null ? inspection.getInspectionDate().toString() : EMPTY;
        return String.format("%s 的质检（%s）", date, batchTitle);
    }

    /** 质检结果的中文名; 没有值写「—」, 不认识的原样保留。 */
    static String resultLabel(String code) {
        if (code == null) {
            return EMPTY;
        }
        switch (code.toUpperCase()) {
            case "PENDING":
                return "待检验";
            case "PASS":
                return "合格";
            case "FAIL":
                return "不合格";
            case "CONDITIONAL":
                return "条件通过";
            default:
                return code;
        }
    }
}
