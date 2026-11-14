package com.cretas.aims.dto.outsource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 委外单接口的请求与视图。设计见 {@code docs/specs/outsource-closed-loop-design.md}。
 *
 * <p>累计量 (已收 / 返工待收 / 加工费 / 委外厂结存) 一律由服务端算, 请求里没有这些字段。
 */
public final class OutsourceDtos {

    private OutsourceDtos() {}

    // ==================== 请求 ====================

    public record CreateOrderRequest(String supplierId, LocalDate expectedReturnDate, String remark,
                                     List<CreateLineRequest> lines) {}

    /**
     * outsourceType=PROCESS 时必须带 productionPlanId + workProcessTaskId (工序委外);
     * WHOLE 整单委外不挂工单。
     */
    public record CreateLineRequest(String outsourceType, String productionPlanId, Long workProcessTaskId,
                                    String outputKind, String productTypeId, String unit,
                                    BigDecimal plannedQuantity, BigDecimal unitFee, BigDecimal taxRate) {}

    /** 一次发料可以多批次 (半成品 + 包材 + 辅料)。 */
    public record IssueRequest(String clientRequestId, LocalDate bizDate, List<BatchQuantity> items) {}

    /** 退料: batchId = 委外仓批次 (系统退回它的原批次)。 */
    public record MaterialReturnRequest(String clientRequestId, LocalDate bizDate, List<BatchQuantity> items) {}

    /**
     * 收货: 合格数 + 不良数; consumptions 为空时按系统建议耗用。
     * 先抵扣返工待收 (不耗料), 其余必须有耗用。
     */
    public record ReceiveRequest(String clientRequestId, LocalDate bizDate, BigDecimal goodQuantity,
                                 BigDecimal defectQuantity, List<BatchQuantity> consumptions) {}

    /** 退货返工: sourceBatchId = 本行收货产出的批次。 */
    public record GoodsReturnRequest(String clientRequestId, LocalDate bizDate, String sourceBatchId,
                                     BigDecimal quantity, String reason) {}

    /** 损耗: batchId = 委外仓批次, reason 必填。 */
    public record LossRequest(String clientRequestId, LocalDate bizDate, List<BatchQuantity> items, String reason) {}

    public record BatchQuantity(String batchId, BigDecimal quantity) {}

    // ==================== 视图 ====================

    public record OrderSummary(String id, String orderNo, String supplierId, String supplierName, String status,
                               LocalDate expectedReturnDate, int lineCount, BigDecimal plannedQuantity,
                               BigDecimal receivedGoodQuantity, BigDecimal outsourceBalance,
                               BigDecimal feeAmount, LocalDateTime createdAt) {}

    public record OrderDetail(String id, String orderNo, String supplierId, String supplierName, String status,
                              String outsourceWarehouseId, LocalDate expectedReturnDate, String remark,
                              LocalDateTime releasedAt, LocalDateTime closedAt, LocalDateTime createdAt,
                              BigDecimal feeAmount, List<LineView> lines, List<BalanceView> balances,
                              List<MovementView> movements) {}

    public record LineView(String id, int lineNo, String outsourceType, String productionPlanId,
                           String productionPlanNumber, Long workProcessTaskId, String processName,
                           String outputKind, String productTypeId, String outputName, String unit,
                           BigDecimal plannedQuantity, BigDecimal unitFee, BigDecimal taxRate,
                           BigDecimal receivedGoodQuantity, BigDecimal receivedDefectQuantity,
                           BigDecimal returnedGoodsQuantity, BigDecimal reworkPendingQuantity,
                           BigDecimal remainingToReceive, BigDecimal feeAmount, BigDecimal outsourceBalance,
                           String status) {}

    /** 委外厂结存: 委外仓里本单各批次的当前数量。 */
    public record BalanceView(String lineId, String batchId, String batchNumber, String materialTypeId,
                              String productTypeId, String name, String sourceBatchId, BigDecimal issuedQuantity,
                              BigDecimal currentQuantity, String unit, BigDecimal unitPrice) {}

    public record MovementView(String id, String lineId, String movementType, String parentMovementId,
                               LocalDate bizDate, String sourceBatchId, String sourceBatchNumber,
                               String targetBatchId, String targetBatchNumber, String targetBatchKind,
                               BigDecimal quantity, BigDecimal defectQuantity, String unit, BigDecimal unitCost,
                               BigDecimal amount, String apTransactionId, String qualityReturnOrderId,
                               String reason, Long createdBy, LocalDateTime createdAt) {}

    /** 收货建议: 按本次数量占剩余应收的比例给出各委外仓批次的耗用量。 */
    public record ReceiveSuggestion(String lineId, BigDecimal remainingToReceive, BigDecimal reworkPendingQuantity,
                                    BigDecimal reworkPart, BigDecimal newPart, BigDecimal ratio,
                                    List<BatchSuggestion> consumptions) {}

    public record BatchSuggestion(String batchId, String batchNumber, String name, BigDecimal currentQuantity,
                                  BigDecimal suggestedQuantity, String unit) {}

    /** 工单详情里的委外工序。 */
    public record ProcessLink(Long workProcessTaskId, String productionPlanId, String orderId, String orderNo,
                              String orderStatus, String supplierId, String supplierName, String lineId,
                              String lineStatus, BigDecimal plannedQuantity, BigDecimal receivedGoodQuantity,
                              BigDecimal feeAmount) {}
}
