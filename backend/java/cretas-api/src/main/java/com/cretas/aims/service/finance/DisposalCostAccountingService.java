package com.cretas.aims.service.finance;

import com.cretas.aims.entity.DisposalRecord;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.dto.finance.VoucherEntrySpec;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsAdjustmentLogRepository;
import com.cretas.aims.service.voucher.VoucherService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.RoundingMode;
import java.util.List;

/**
 * 财务处置边界。实物报废和销毁的库存减少必须与 POSTED 损失凭证处在同一事务。
 * RECYCLE/RETURN/DONATE 的收入、退供和捐赠口径尚未定义，故明确拒绝进入此边界。
 */
@Service
@RequiredArgsConstructor
public class DisposalCostAccountingService {
    private static final String SOURCE = "DISPOSAL_RECORD";
    private static final String EXPENSE_CODE = "6711";
    private static final String INVENTORY_CODE = "1405";

    private final VoucherService voucherService;
    private final EntityManager entityManager;
    private final AccountingPeriodService accountingPeriodService;
    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;

    @Autowired(required = false)
    private FinishedGoodsAdjustmentLogRepository adjustmentLogRepository;

    /** Must be called before the disposal record and finished-goods row locks. */
    public void lockFactory(String factoryId) {
        if (factoryId == null || factoryId.isBlank()) {
            throw new BusinessException(400, "factoryId 不可为空");
        }
        ProductionCostConsistency.lockFactory(entityManager, factoryId);
    }

    private List<Voucher> findHistory(String factoryId, String id) {
        return entityManager.createQuery(
                "select v from Voucher v where v.factoryId=:factory and v.deletedAt is null and "
                        + "((v.sourceBusinessType='DISPOSAL_RECORD' and v.sourceBusinessId=:id) "
                        + "or (v.sourceBusinessType='DISPOSAL_RECORD_REVISION' and v.sourceBusinessId like :prefix))", Voucher.class)
                .setParameter("factory",factoryId).setParameter("id",id)
                .setParameter("prefix",id+"#%").getResultList();
    }

    /** Bounded, read-only history, including each original's real reversal. */
    @Transactional(readOnly = true)
    public List<Voucher> getAccountingHistory(String factoryId, Long recordId) {
        if (factoryId == null || factoryId.isBlank() || recordId == null || recordId <= 0) {
            throw new BusinessException(400,"缺少工厂或有效报废记录号");
        }
        var result = new java.util.ArrayList<Voucher>();
        for (Voucher original : findHistory(factoryId,String.valueOf(recordId))) {
            if (!factoryId.equals(original.getFactoryId())) throw new BusinessException(409,"报废凭证工厂不匹配");
            result.add(original);
            if (original.getReversalVoucherId() == null) {
                if (original.getStatus() == com.cretas.aims.entity.enums.VoucherStatus.REVERSED) {
                    throw new BusinessException(409,"已冲销的报废凭证缺少红字关联，请财务核对");
                }
                continue;
            }
            Voucher red=entityManager.find(Voucher.class,original.getReversalVoucherId());
            if (original.getStatus() != com.cretas.aims.entity.enums.VoucherStatus.REVERSED
                    || red == null || red.getStatus() != com.cretas.aims.entity.enums.VoucherStatus.POSTED
                    || red.getDeletedAt() != null || !factoryId.equals(red.getFactoryId())
                    || !"VOUCHER_REVERSAL".equals(red.getSourceBusinessType())
                    || !original.getId().equals(red.getOriginalVoucherId())
                    || !original.getId().equals(red.getSourceBusinessId())) {
                throw new BusinessException(409,"报废红字凭证与原凭证关联不完整，请财务核对");
            }
            result.add(red);
        }
        result.sort(java.util.Comparator.comparing(Voucher::getVoucherNumber));
        return result;
    }

    @Transactional
    public Voucher postApprovedDisposal(DisposalRecord record, BigDecimal amount, Long actorId) {
        assertPhysicalDisposal(record);
        if (actorId == null || actorId <= 0 || record == null || record.getId() == null
                || record.getFactoryId() == null || record.getFactoryId().isBlank()) {
            throw new BusinessException(400, "处置过账缺少真实工厂、记录或操作人").withCode("DISPOSAL_ACTOR_REQUIRED");
        }
        if (amount == null || amount.signum() < 0) {
            throw new BusinessException(409, "处置缺少真实成本，不能生成费用凭证")
                    .withCode("DISPOSAL_COST_REQUIRED");
        }
        if (record.getFinishedGoodsBatchId() == null && record.getMaterialBatchId() == null) {
            throw new BusinessException(409, "处置缺少可核验库存批次").withCode("DISPOSAL_COST_EVIDENCE_INCOMPLETE");
        }
        LocalDate date = disposalDate(record);
        accountingPeriodService.assertOpen(record.getFactoryId(), date.getYear(), date.getMonthValue());
        String id = String.valueOf(record.getId());
        Voucher existing = voucherService.findBySourceBusiness(SOURCE, id).orElse(null);
        if (existing != null) {
            validatePostedVoucher(existing, record, date, amount, SOURCE, id);
            return existing;
        }
        if (amount.signum() == 0) return null;
        return voucherService.createManual(
                record.getFactoryId(), VoucherType.EXPENSE, date,
                entries(amount, inventoryCode(record), "处置损失 " + id), SOURCE, id,
                "成品" + record.getDisposalType() + "处置损失过账", actorId);
    }

    /**
     * 源成本更正后，由成本同步事务调用。每个批准处置都锁定后重新核价；原 POSTED 凭证保留，
     * 以红字凭证冲销，再以 revision 来源生成新 POSTED 凭证。无完整成本或期间未开放时整体拒绝。
     */
    @Transactional
    public void revalueForBatch(String factoryId, String finishedGoodsBatchId, Long actorId) {
        if (actorId == null || actorId <= 0) {
            throw new BusinessException(400, "重估必须提供真实操作人").withCode("DISPOSAL_ACTOR_REQUIRED");
        }
        lockFactory(factoryId);
        var batch = finishedGoodsBatchRepository.findByIdAndFactoryIdForUpdate(finishedGoodsBatchId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "成品批次不存在或不属于当前工厂"));
        BigDecimal unitCost = batch.getUnitCost();
        if (unitCost == null || unitCost.signum() < 0) {
            throw new BusinessException(409, "成本证据不完整，不能重估报废")
                    .withCode("DISPOSAL_COST_EVIDENCE_INCOMPLETE");
        }
        List<DisposalRecord> records = entityManager.createQuery(
                        "select d from DisposalRecord d where d.factoryId=:factory and "
                                + "d.finishedGoodsBatchId=:batch and d.isApproved=true", DisposalRecord.class)
                .setParameter("factory", factoryId).setParameter("batch", finishedGoodsBatchId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        for (DisposalRecord record : records) {
            assertPhysicalDisposal(record);
            if (record.getDisposalQuantity() == null || record.getDisposalQuantity().signum() <= 0) {
                throw new BusinessException(409, "处置数量缺失，不能重估报废").withCode("DISPOSAL_QUANTITY_REQUIRED");
            }
            LocalDate date = disposalDate(record);
            accountingPeriodService.assertOpen(factoryId, date.getYear(), date.getMonthValue());
            BigDecimal revised = record.getDisposalQuantity().multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
            BigDecimal previous = record.getActualLoss();
            String id = String.valueOf(record.getId());
            List<Voucher> history = findHistory(factoryId,id);
            Voucher active = history.stream().filter(v -> v.getStatus() == com.cretas.aims.entity.enums.VoucherStatus.POSTED)
                    .reduce((a, b) -> { throw new BusinessException(409, "同一处置存在多个有效凭证").withCode("DISPOSAL_VOUCHER_DUPLICATE"); })
                    .orElse(null);
            BigDecimal activeAmount = active == null ? null : voucherAmount(active);
            if (active != null) {
                validatePostedVoucher(active, record, date, activeAmount,
                        active.getSourceBusinessType(), active.getSourceBusinessId());
            }
            if (activeAmount != null && activeAmount.compareTo(revised) == 0) {
                if (previous == null || previous.compareTo(revised) != 0) {
                    record.setActualLoss(revised);
                    entityManager.merge(record);
                }
                continue;
            }
            if (previous != null && activeAmount != null && previous.compareTo(activeAmount) != 0) {
                throw new BusinessException(409, "处置实际损失与有效凭证金额不一致").withCode("DISPOSAL_VOUCHER_AMOUNT_MISMATCH");
            }
            if (active != null) {
                voucherService.reverseDisposalCostVoucher(factoryId,record.getId(),active.getId(),actorId);
            }
            String revision = id + "#r" + (history.size() + 1);
            boolean allHistoryReversed = history.stream().allMatch(v ->
                    v.getStatus() == com.cretas.aims.entity.enums.VoucherStatus.REVERSED
                            || v.getStatus() == com.cretas.aims.entity.enums.VoucherStatus.VOID);
            if (active == null && !history.isEmpty()
                    && (previous == null || previous.signum() != 0 || !allHistoryReversed)) {
                throw new BusinessException(409, "处置历史存在但没有唯一有效凭证，需人工核对后补齐")
                        .withCode("DISPOSAL_VOUCHER_HISTORY_INCOMPLETE");
            }
            if (active == null && history.isEmpty() && !hasPhysicalEvidence(factoryId, finishedGoodsBatchId,
                    record.getId(), record.getDisposalQuantity())) {
                throw new BusinessException(409, "历史报废缺少 SCRAP 数量日志证据，不能补齐凭证")
                        .withCode("DISPOSAL_PHYSICAL_EVIDENCE_REQUIRED");
            }
            if (revised.signum() > 0) {
                voucherService.createManual(factoryId, VoucherType.EXPENSE, date,
                        entries(revised, inventoryCode(record), "报废成本更正 " + id),
                        active == null && history.isEmpty() ? SOURCE : "DISPOSAL_RECORD_REVISION",
                        active == null && history.isEmpty() ? id : revision,
                        "报废成本更正后重新过账", actorId);
            }
            record.setActualLoss(revised);
            entityManager.merge(record);
        }
    }

    private void assertPhysicalDisposal(DisposalRecord record) {
        if (record == null || !"SCRAP".equals(record.getDisposalType())
                && !"DESTROY".equals(record.getDisposalType())) {
            throw new BusinessException(409, "仅 SCRAP/DESTROY 支持费用过账；RECYCLE/RETURN/DONATE 口径尚未定义")
                    .withCode("DISPOSAL_ACCOUNTING_POLICY_REQUIRED");
        }
    }

    private LocalDate disposalDate(DisposalRecord record) {
        LocalDateTime value = record.getDisposalDate();
        if (value == null) throw new BusinessException(409, "处置日期缺失").withCode("DISPOSAL_DATE_REQUIRED");
        return value.toLocalDate();
    }

    private static String inventoryCode(DisposalRecord record) {
        if (record.getFinishedGoodsBatchId() != null) return INVENTORY_CODE;
        if (record.getMaterialBatchId() != null) return "1403";
        throw new BusinessException(409, "处置库存科目无法确定").withCode("DISPOSAL_INVENTORY_ACCOUNT_REQUIRED");
    }

    private List<VoucherEntrySpec> entries(BigDecimal amount, String inventoryCode, String description) {
        return List.of(new VoucherEntrySpec(EXPENSE_CODE, "存货报废损失", amount, null, description),
                new VoucherEntrySpec(inventoryCode, "库存", null, amount, description));
    }

    private static BigDecimal voucherAmount(Voucher voucher) {
        if (voucher.getTotalDebit() == null || voucher.getTotalCredit() == null
                || voucher.getTotalDebit().compareTo(voucher.getTotalCredit()) != 0
                || voucher.getEntries() == null || voucher.getEntries().isEmpty()) {
            throw new BusinessException(409, "处置历史凭证不平或缺少分录").withCode("DISPOSAL_VOUCHER_INVALID");
        }
        return voucher.getTotalDebit();
    }

    public static void validatePostedVoucher(Voucher voucher, DisposalRecord record, LocalDate date,
                                 BigDecimal expectedAmount, String sourceType, String sourceId) {
        if (voucher.getStatus() != com.cretas.aims.entity.enums.VoucherStatus.POSTED
                || voucher.getVoucherType() != VoucherType.EXPENSE
                || !record.getFactoryId().equals(voucher.getFactoryId())
                || !sourceType.equals(voucher.getSourceBusinessType())
                || !sourceId.equals(voucher.getSourceBusinessId())
                || !date.equals(voucher.getVoucherDate())
                || voucherAmount(voucher).compareTo(expectedAmount) != 0) {
            throw new BusinessException(409, "处置已有凭证状态、来源、日期或金额不一致")
                    .withCode("DISPOSAL_VOUCHER_INVALID");
        }
        String inventory = inventoryCode(record);
        if (voucher.getEntries().size() != 2) {
            throw new BusinessException(409, "处置凭证分录结构不正确").withCode("DISPOSAL_VOUCHER_INVALID");
        }
        boolean expense = false;
        boolean stock = false;
        for (var entry : voucher.getEntries()) {
            BigDecimal debit = entry.getDebit() == null ? BigDecimal.ZERO : entry.getDebit();
            BigDecimal credit = entry.getCredit() == null ? BigDecimal.ZERO : entry.getCredit();
            if ("6711".equals(entry.getSubjectCode()) && debit.compareTo(expectedAmount) == 0
                    && credit.signum() == 0) expense = true;
            if (inventory.equals(entry.getSubjectCode()) && credit.compareTo(expectedAmount) == 0
                    && debit.signum() == 0) stock = true;
        }
        if (!expense || !stock) {
            throw new BusinessException(409, "处置凭证必须为 6711 借、库存科目贷")
                    .withCode("DISPOSAL_VOUCHER_INVALID");
        }
    }

    private boolean hasPhysicalEvidence(String factoryId, String batchId, Long recordId, BigDecimal quantity) {
        if (adjustmentLogRepository == null) return false;
        return adjustmentLogRepository.findByBatchIdOrderByCreatedAtDesc(batchId).stream()
                .filter(log -> factoryId.equals(log.getFactoryId()) && "SCRAP".equals(log.getReferenceType())
                        && log.getReason() != null && log.getReason().contains("报损审批 [id=" + recordId + "]"))
                .map(log -> log.getAdjustmentQuantity() == null ? BigDecimal.ZERO : log.getAdjustmentQuantity().abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(quantity) == 0;
    }
}
