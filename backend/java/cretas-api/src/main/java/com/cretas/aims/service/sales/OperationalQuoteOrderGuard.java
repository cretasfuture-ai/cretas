package com.cretas.aims.service.sales;

import com.cretas.aims.dto.inventory.CreateSalesOrderRequest;
import com.cretas.aims.dto.inventory.UpdateSalesOrderRequest;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.entity.sales.OperationalQuote;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.sales.OperationalQuoteRepository;
import com.cretas.aims.security.DataScopeResolver;
import com.cretas.aims.service.unit.UnitContractService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Validates an optional quote reference inside the surrounding order transaction. */
@Component
@RequiredArgsConstructor
public class OperationalQuoteOrderGuard {
    private final OperationalQuoteRepository quotes;
    private final UnitContractService units;
    private final DataScopeResolver dataScopeResolver;
    @PersistenceContext private EntityManager entityManager;

    public BigDecimal validateCreate(String factoryId, CreateSalesOrderRequest request) {
        if (request.getQuoteId() == null || request.getQuoteId().isBlank()) return null;
        rejectHeaderDiscount(request.getDiscountAmount());
        return validate(factoryId, request.getCustomerId(), request.getQuoteId(), request.getItems() == null ? List.of() :
                request.getItems().stream().map(i -> new Line(i.getProductTypeId(), i.getUnit(), i.getQuantity(),
                        i.getUnitPrice(), i.getTaxRate(), i.getDiscountRate())).toList());
    }

    public BigDecimal validateUpdate(String factoryId, SalesOrder order, UpdateSalesOrderRequest request, List<SalesOrderItem> existing) {
        rejectHeaderDiscount(order.getDiscountAmount());
        if (request.getItems() == null || request.getItems().isEmpty()) {
            validateConfirmation(factoryId, order, existing);
            return existing.get(0).getQuotedGrossUnitPrice();
        }
        return validate(factoryId, order.getCustomerId(), order.getQuoteId(), request.getItems().stream().map(i ->
                new Line(i.getProductTypeId(), i.getUnit(), i.getQuantity(), i.getUnitPrice(), i.getTaxRate(), i.getDiscountRate())).toList());
    }

    public void validateConfirmation(String factoryId, SalesOrder order, List<SalesOrderItem> items) {
        rejectHeaderDiscount(order.getDiscountAmount());
        BigDecimal approved = validate(factoryId, order.getCustomerId(), order.getQuoteId(), items.stream().map(i ->
                new Line(i.getProductTypeId(), i.getUnit(), i.getQuantity(), i.getUnitPrice(), i.getTaxRate(), i.getDiscountRate())).toList());
        if (items.stream().anyMatch(i -> i.getQuotedGrossUnitPrice() == null || i.getQuotedGrossUnitPrice().compareTo(approved) != 0))
            throw blocked("引用报价缺少一致的含税价快照，请编辑完整明细或重新建单后再确认");
    }

    private BigDecimal validate(String factoryId, String customerId, String quoteId, List<Line> lines) {
        OperationalQuote quote = quotes.findByIdAndFactoryIdAndDeletedAtIsNull(quoteId, factoryId)
                .orElseThrow(() -> blocked("引用报价不存在或不属于当前工厂"));
        // This is a quote reference inside a sales-order route; do not use the
        // resource-specific shortcut which intentionally skips other resources.
        dataScopeResolver.assertCanAccess(factoryId, quote.getSubmittedByUserId());
        // Refresh the managed entity as well as locking it: a stale persistence
        // context must not approve a quote already revised in another transaction.
        entityManager.refresh(quote, LockModeType.PESSIMISTIC_READ);
        if (quote.getDeletedAt() != null) throw blocked("引用报价已删除，不能下单");
        if (!"APPROVED".equals(quote.getStatus())) throw blocked("引用报价尚未批准或已被修订，不能下单");
        if (quote.getValidUntil() != null && quote.getValidUntil().isBefore(LocalDate.now())) throw blocked("引用报价已过期，不能下单");
        if (!Objects.equals(customerId, quote.getCustomerId())) throw blocked("订单客户与引用报价不一致");
        if (lines.isEmpty()) throw blocked("引用报价的订单必须包含产品明细");
        BigDecimal totalQuantity = BigDecimal.ZERO;
        for (Line line : lines) {
            if (!Objects.equals(line.productId(), quote.getProductTypeId())) throw blocked("订单产品与引用报价不一致");
            if (!sameUnit(factoryId, line.unit(), quote.getUnit())) throw blocked("订单单位与引用报价不一致，请重新报价而非猜测换算");
            if (line.quantity() == null || line.quantity().signum() <= 0) throw blocked("引用报价的订单数量必须大于零");
            if (line.taxRate() == null || line.taxRate().signum() < 0 || line.taxRate().compareTo(new BigDecimal("100")) > 0)
                throw blocked("引用报价下单必须明确有效税率");
            if (line.discount() != null && line.discount().signum() != 0) throw blocked("引用报价下单不支持额外折扣，请重新报价审批");
            BigDecimal expectedNet = quote.getUnitPrice() == null ? null : quote.getUnitPrice()
                    .divide(BigDecimal.ONE.add(line.taxRate().movePointLeft(2)), 4, RoundingMode.HALF_UP);
            if (line.netPrice() == null || expectedNet == null || line.netPrice().setScale(4, RoundingMode.HALF_UP).compareTo(expectedNet) != 0)
                throw blocked("订单含税单价与已批准报价不一致，请重新报价审批");
            totalQuantity = totalQuantity.add(line.quantity());
        }
        if (quote.getMinOrderQty() != null && totalQuantity.compareTo(quote.getMinOrderQty()) < 0)
            throw blocked("订单数量低于引用报价的最小起订量");
        return quote.getUnitPrice();
    }

    private void rejectHeaderDiscount(BigDecimal discount) {
        if (discount != null && discount.signum() != 0) throw blocked("引用报价下单不支持整单额外折扣，请重新报价审批");
    }

    private boolean sameUnit(String factoryId, String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) return false;
        String a = units.normalize(factoryId, left).code();
        String b = units.normalize(factoryId, right).code();
        return a != null && a.equals(b);
    }

    private static BusinessException blocked(String message) {
        return new BusinessException(409, message).withHint("请刷新报价，选择已批准且与本单一致的报价，或先修订并重新审批")
                .withHintTarget("quoteId");
    }

    private record Line(String productId, String unit, BigDecimal quantity, BigDecimal netPrice, BigDecimal taxRate, BigDecimal discount) {}
}
