package com.cretas.aims.service.gate;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.quality.release.QcInspectionGuard;
import com.cretas.aims.service.sales.FinishedGoodsBatchSaleabilityGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 只读: 「这批为什么领不了 / 出库为什么卡住」。按批次号找到批次, 把它放进各道闸再跑一遍,
 * 收集各闸拒绝时带的结构化诊断 —— 与写路径拒绝是同一份代码产出的同一个对象, 不另造一套规则。
 * 不执行、不修改任何数据。涉及具体计划/订单的闸 (归属、缺料开工) 需要上下文, 这里只回答批次自身被拦的原因。
 */
@Service
@RequiredArgsConstructor
public class GateDiagnosisService {

    private final MaterialBatchRepository materialBatchRepository;
    private final SemiFinishedInventoryRepository semiRepository;
    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;

    public List<GateDiagnosis> diagnoseBatch(String factoryId, String batchNumber) {
        var material = materialBatchRepository.findByFactoryIdAndBatchNumber(factoryId, batchNumber);
        var semi = semiRepository.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(factoryId, batchNumber);
        var finished = finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(factoryId, batchNumber);
        if (material.isEmpty() && semi.isEmpty() && finished.isEmpty()) {
            throw new BusinessException(404, "当前工厂未找到该批次，请核对批次号")
                    .withCode("BATCH_NOT_FOUND");
        }
        List<GateDiagnosis> out = new ArrayList<>();
        material.ifPresent(mb -> {
            MaterialBatchStateGate.diagnose(mb).ifPresent(out::add);
            if ("PRODUCTION_BATCH".equals(mb.getSourceDocType())) {
                collect(out, () -> QcInspectionGuard.assertWipBatchDrawable(mb, batchNumber, null));
            }
        });
        semi.ifPresent(sfi -> collect(out, () -> QcInspectionGuard.assertSemiDrawable(sfi, null)));
        finished.ifPresent(fg ->
                collect(out, () -> FinishedGoodsBatchSaleabilityGuard.assertSaleable(fg, null, LocalDate.now())));
        return out;
    }

    /** 跑一道闸, 把它抛出的诊断收进来; 没抛 = 这道闸放行。 */
    public static void collect(List<GateDiagnosis> out, Runnable gate) {
        try {
            gate.run();
        } catch (BusinessException e) {
            out.addAll(e.getDiagnoses());
        }
    }
}
