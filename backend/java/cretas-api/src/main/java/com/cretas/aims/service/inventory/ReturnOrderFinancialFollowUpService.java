package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.ReturnFinancialFollowUpDTO;
import com.cretas.aims.entity.enums.ArApApprovalStatus;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.VoucherRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.service.finance.ArApService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Source-scoped financial readback and recovery; never repeats stock movements. */
@Service
@RequiredArgsConstructor
public class ReturnOrderFinancialFollowUpService {
    private static final String SOURCE = "RETURN_ORDER";
    private final ReturnOrderRepository returnOrderRepository;
    private final ArApTransactionRepository transactionRepository;
    private final VoucherRepository voucherRepository;
    private final ArApService arApService;
    @PersistenceContext private EntityManager entityManager;

    @Transactional(readOnly = true)
    public ReturnFinancialFollowUpDTO get(String factoryId, String returnOrderId) {
        ReturnOrder order = load(factoryId, returnOrderId);
        List<ArApTransaction> rows = adjustments(factoryId, order);
        String state = ReturnAdjustmentEvidence.state(order, rows);
        Voucher voucher = voucherRepository.findBySourceBusinessTypeAndSourceBusinessIdAndDeletedAtIsNull(
                SOURCE, returnOrderId).orElse(null);
        if (voucher != null && !Objects.equals(factoryId, voucher.getFactoryId())) {
            throw new BusinessException(403, "退货凭证不属于当前工厂");
        }
        return ReturnFinancialFollowUpDTO.builder().returnOrderId(order.getId())
                .returnNumber(order.getReturnNumber()).status(state)
                .canResubmit(eligible(order) && ("REJECTED".equals(state) || "MISSING".equals(state)))
                .adjustments(rows).voucher(voucher == null ? null : ReturnFinancialFollowUpDTO.VoucherRef.builder()
                        .id(voucher.getId()).voucherNumber(voucher.getVoucherNumber())
                        .status(voucher.getStatus() == null ? "UNKNOWN" : voucher.getStatus().name()).build())
                .build();
    }

    @Transactional
    public ArApTransaction resubmit(String factoryId, String returnOrderId, Long actorId, String reason) {
        if (actorId == null || actorId <= 0 || reason == null || reason.isBlank() || reason.length() > 200) {
            throw new BusinessException(400, "登录身份和重提原因不能为空，原因最多200字");
        }
        ReturnOrder order = load(factoryId, returnOrderId);
        // The same parent row is locked by the original return completion path.
        // Serializing on it prevents two recovery requests from creating two credits.
        if (!ReturnOrderRowLock.lockAndRefresh(entityManager, order, factoryId)) {
            throw new BusinessException(409, "退货单已失效，请刷新后重试")
                    .withHint("请刷新销售退货详情，确认退货仍为有效状态后重新提交应收调整");
        }
        if (!Objects.equals(factoryId, order.getFactoryId()) || !eligible(order)) {
            throw new BusinessException(409, "仅已完成的实物销售退货可重新提交应收调整")
                    .withCode("RETURN_ADJUSTMENT_NOT_ELIGIBLE")
                    .withHint("请在销售退货详情完成实物入库并经审批，再提交应收调整");
        }
        List<ArApTransaction> rows = adjustments(factoryId, order);
        String state = ReturnAdjustmentEvidence.state(order, rows);
        if ("APPROVED".equals(state)) {
            throw new BusinessException(409, "该退货应收调整已经生效，请勿重复提交")
                    .withCode("RETURN_ADJUSTMENT_ALREADY_APPROVED")
                    .withHint("请打开退货财务跟进确认已有应收调整，无需重复提交");
        }
        if ("PENDING".equals(state)) {
            return rows.stream().filter(row -> row.getApprovalStatus() == ArApApprovalStatus.PENDING)
                    .findFirst().orElseThrow();
        }
        if (!"REJECTED".equals(state) && !"MISSING".equals(state)) {
            throw new BusinessException(409, "原退货调整证据不一致，请先由财务核对")
                    .withCode("RETURN_ADJUSTMENT_INCONSISTENT")
                    .withHint("请打开退货财务跟进和应收应付调整列表，核对原调整后再重提");
        }
        String previous = rows.stream().map(ArApTransaction::getId).sorted().limit(1).findFirst().orElse("");
        return arApService.recordAdjustment(factoryId, CounterpartyType.CUSTOMER, order.getCounterpartyId(),
                order.getReceivableAmount().negate(), actorId,
                "销售退货应收调整重提-" + order.getReturnNumber() + "；原因：" + reason.trim()
                        + "；历史调整：" + (previous.isEmpty() ? "缺失待补" : previous), SOURCE, order.getId());
    }

    private ReturnOrder load(String factoryId, String id) {
        if (factoryId == null || factoryId.isBlank() || id == null || id.isBlank()) {
            throw new BusinessException(400, "工厂和退货单不能为空");
        }
        ReturnOrder order = returnOrderRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "退货单不存在"));
        if (!Objects.equals(factoryId, order.getFactoryId())) throw new BusinessException(403, "退货单不属于当前工厂");
        if (order.getReturnType() != ReturnType.SALES_RETURN) throw new BusinessException(409, "本入口仅处理销售退货应收调整")
                .withHint("请从销售退货详情的财务跟进入口提交应收调整");
        return order;
    }

    private List<ArApTransaction> adjustments(String factoryId, ReturnOrder order) {
        return transactionRepository.findByFactoryIdAndSourceTypeAndSourceIdInAndTransactionTypeAndDeletedAtIsNull(
                        factoryId, SOURCE, List.of(order.getId()), ArApTransactionType.AR_ADJUSTMENT)
                .stream().sorted(Comparator.comparing(ArApTransaction::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(ArApTransaction::getId)).toList();
    }

    private boolean eligible(ReturnOrder order) {
        return order.getStatus() == ReturnOrderStatus.COMPLETED && Boolean.TRUE.equals(order.getWithGoods())
                && order.getTotalAmount() != null && order.getTotalAmount().signum() > 0
                && order.getCounterpartyId() != null && !order.getCounterpartyId().isBlank();
    }

}
