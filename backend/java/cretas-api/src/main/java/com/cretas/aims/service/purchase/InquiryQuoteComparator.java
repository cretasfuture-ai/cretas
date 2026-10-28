package com.cretas.aims.service.purchase;

import com.cretas.aims.dto.purchase.InquiryQuoteComparisonDTO;
import com.cretas.aims.dto.purchase.InquiryQuoteComparisonDTO.Issue;
import com.cretas.aims.dto.purchase.InquiryQuoteComparisonDTO.Row;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.SupplierMaterial;
import com.cretas.aims.entity.SupplierMaterialPurchaseSpec;
import com.cretas.aims.entity.purchase.InquiryQuote;
import com.cretas.aims.entity.purchase.InquiryQuoteSupplierPrice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 报价可比性判定 (L3, S5-01 / S5-02). 纯函数: 输入一次性查好, 不碰数据库。
 *
 * <p>判定分三层:
 * <ol>
 *   <li><b>可比</b>: 币种、计价单位、规格、税率都已知且与基准一致。任一不满足 → 不可比,
 *       逐条给原因, ⛔ 不参与排序。未知一律当「缺依据」, ⛔ 不当 0、不换算。</li>
 *   <li><b>可选定</b>: 可比 + 在有效期内 + 价格已生效 + 不低于起订量 + 供应商与供应关系可用
 *       + 采购包装规格可唯一确定。</li>
 *   <li><b>提示</b>: 交期晚于需求日、未注明有效期 / 交期 —— 不阻止选定。</li>
 * </ol>
 */
public final class InquiryQuoteComparator {

    /** 采购单不带币种字段, 一律按人民币记账; 其它币种没有汇率依据, 不能拿来比。 */
    public static final String PURCHASE_CURRENCY = "CNY";

    public static final String SOURCE_QUOTE = "QUOTE";
    public static final String SOURCE_SUPPLIER_MATERIAL = "SUPPLIER_MATERIAL";

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private InquiryQuoteComparator() {
    }

    /** 比较所需的全部外部事实 (由服务层按工厂查好)。 */
    public record Facts(
            BigDecimal referenceTaxRate,
            String referenceTaxRateSource,
            LocalDate today,
            Map<String, Supplier> suppliersById,
            Map<String, SupplierMaterial> relationsBySupplierId,
            Map<String, List<SupplierMaterialPurchaseSpec>> activeSpecsByRelationId) {
    }

    public static InquiryQuoteComparisonDTO compare(InquiryQuote inquiry,
                                                   List<InquiryQuoteSupplierPrice> prices,
                                                   Facts facts) {
        String refSpec = blankToNull(inquiry.getSpecification());
        Set<BigDecimal> knownTaxRates = prices.stream()
                .map(InquiryQuoteSupplierPrice::getTaxRate)
                .filter(Objects::nonNull)
                .map(BigDecimal::stripTrailingZeros)
                .collect(Collectors.toCollection(TreeSet::new));

        List<Row> rows = new ArrayList<>();
        for (InquiryQuoteSupplierPrice price : prices) {
            rows.add(evaluate(inquiry, refSpec, knownTaxRates, price, facts));
        }

        List<Row> ranked = rows.stream()
                .filter(InquiryQuoteComparator::isRankable)
                .sorted(Comparator.comparing(Row::getUnitPrice)
                        .thenComparing(r -> r.getSupplierName() == null ? "" : r.getSupplierName()))
                .collect(Collectors.toList());
        int rank = 0;
        BigDecimal previous = null;
        for (int i = 0; i < ranked.size(); i++) {
            Row row = ranked.get(i);
            if (previous == null || row.getUnitPrice().compareTo(previous) != 0) {
                rank = i + 1;
                previous = row.getUnitPrice();
            }
            row.setRank(rank);
        }

        List<Row> ordered = new ArrayList<>(ranked);
        rows.stream()
                .filter(r -> r.getRank() == null)
                .sorted(Comparator.comparing((Row r) -> !r.isComparable())
                        .thenComparing(r -> r.getSupplierName() == null ? "" : r.getSupplierName()))
                .forEach(ordered::add);

        return InquiryQuoteComparisonDTO.builder()
                .inquiryId(inquiry.getId())
                .inquiryNumber(inquiry.getInquiryNumber())
                .materialTypeId(inquiry.getMaterialTypeId())
                .materialName(inquiry.getMaterialName())
                .quantity(inquiry.getQuantity())
                .referenceUnit(inquiry.getUnit())
                .referenceCurrency(PURCHASE_CURRENCY)
                .referenceSpecification(refSpec)
                .referenceTaxRate(facts.referenceTaxRate())
                .referenceTaxRateSource(facts.referenceTaxRateSource())
                .requiredDate(inquiry.getRequiredDate())
                .evaluatedOn(facts.today())
                .rankingBasis("同币种、同计价单位、同规格、同税率下按未税单价从低到高; 不可比与不在有效期内的报价不排序")
                .sourcePurchaseOrderId(inquiry.getSourcePurchaseOrderId())
                .comparableCount((int) rows.stream().filter(Row::isComparable).count())
                .selectableCount((int) rows.stream().filter(Row::isSelectable).count())
                .rows(ordered)
                .build();
    }

    /** 选定入口用: 单条报价的判定结果 (与比价页同一套规则, 不另写一份)。 */
    public static Row evaluateOne(InquiryQuote inquiry, List<InquiryQuoteSupplierPrice> allPrices,
                                  InquiryQuoteSupplierPrice target, Facts facts) {
        return compare(inquiry, allPrices, facts).getRows().stream()
                .filter(r -> Objects.equals(r.getPriceId(), target.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("报价不在本核价单内: " + target.getId()));
    }

    private static boolean isRankable(Row row) {
        return row.isComparable() && row.getBlockingReasons().stream()
                .noneMatch(i -> "QUOTE_EXPIRED".equals(i.getCode())
                        || "PRICE_NOT_YET_EFFECTIVE".equals(i.getCode()));
    }

    private static Row evaluate(InquiryQuote inquiry, String refSpec, Set<BigDecimal> knownTaxRates,
                                InquiryQuoteSupplierPrice price, Facts facts) {
        Supplier supplier = facts.suppliersById().get(price.getSupplierId());
        Row row = Row.builder()
                .priceId(price.getId())
                .supplierId(price.getSupplierId())
                .supplierName(supplier != null ? supplier.getName() : price.getSupplierName())
                .unitPrice(price.getUnitPrice())
                .taxRate(price.getTaxRate())
                .currency(price.getCurrency())
                .quotedUnit(price.getQuotedUnit())
                .specification(price.getSpecification())
                .validUntil(price.getValidUntil())
                .priceEffectiveDate(price.getPriceEffectiveDate())
                .build();
        List<Issue> notComparable = row.getNotComparableReasons();

        // ---- 币种 ----
        String currency = blankToNull(price.getCurrency());
        if (currency == null) {
            notComparable.add(new Issue("CURRENCY_UNKNOWN", "报价未注明币种"));
        } else if (!PURCHASE_CURRENCY.equalsIgnoreCase(currency)) {
            notComparable.add(new Issue("CURRENCY_MISMATCH", String.format(
                    "报价币种 %s 与采购单币种 %s 不同, 没有汇率依据不能换算比较", currency, PURCHASE_CURRENCY)));
        }

        // ---- 计价单位 (不做计数↔重量换算) ----
        String unit = blankToNull(price.getQuotedUnit());
        if (unit == null) {
            notComparable.add(new Issue("UNIT_UNKNOWN", "报价未注明计价单位"));
        } else if (!sameText(unit, inquiry.getUnit())) {
            notComparable.add(new Issue("UNIT_MISMATCH", String.format(
                    "报价计价单位「%s」与询价单位「%s」不同, 不做数量与重量换算", unit, inquiry.getUnit())));
        }

        // ---- 规格 ----
        if (refSpec != null) {
            String spec = blankToNull(price.getSpecification());
            if (spec == null) {
                notComparable.add(new Issue("SPEC_UNKNOWN",
                        String.format("询价要求规格「%s」, 报价未注明规格", refSpec)));
            } else if (!sameText(spec, refSpec)) {
                notComparable.add(new Issue("SPEC_MISMATCH",
                        String.format("报价规格「%s」与询价规格「%s」不同", spec, refSpec)));
            }
        }

        // ---- 税率: 未知 ≠ 0% ----
        BigDecimal tax = price.getTaxRate();
        if (tax == null) {
            notComparable.add(new Issue("TAX_UNKNOWN", "报价税率未知, 不能按 0% 比较"));
        } else if (facts.referenceTaxRate() != null) {
            if (tax.compareTo(facts.referenceTaxRate()) != 0) {
                notComparable.add(new Issue("TAX_MISMATCH", String.format(
                        "报价税率 %s%% 与物料税率 %s%% 不同",
                        plain(tax), plain(facts.referenceTaxRate()))));
            }
        } else if (knownTaxRates.size() > 1) {
            notComparable.add(new Issue("TAX_MISMATCH", String.format(
                    "物料未配置税率, 各报价税率不一致 (%s), 请先在物料档案配置税率",
                    knownTaxRates.stream().map(t -> plain(t) + "%").collect(Collectors.joining("、")))));
        }

        row.setComparable(notComparable.isEmpty());
        if (tax != null && price.getUnitPrice() != null) {
            row.setTaxInclusiveUnitPrice(price.getUnitPrice()
                    .multiply(BigDecimal.ONE.add(tax.divide(HUNDRED, 6, RoundingMode.HALF_UP)))
                    .setScale(4, RoundingMode.HALF_UP));
        }
        if (row.isComparable() && price.getUnitPrice() != null && inquiry.getQuantity() != null) {
            row.setEstimatedAmount(price.getUnitPrice().multiply(inquiry.getQuantity())
                    .setScale(2, RoundingMode.HALF_UP));
        }

        List<Issue> blocking = row.getBlockingReasons();
        List<Issue> warnings = row.getWarnings();
        blocking.addAll(notComparable);

        // ---- 有效期 / 生效日 ----
        LocalDate today = facts.today();
        if (price.getValidUntil() == null) {
            warnings.add(new Issue("VALIDITY_UNKNOWN", "报价未注明有效期"));
        } else if (price.getValidUntil().isBefore(today)) {
            blocking.add(new Issue("QUOTE_EXPIRED",
                    String.format("报价已于 %s 过期, 不能选定", price.getValidUntil())));
        }
        if (price.getPriceEffectiveDate() != null && price.getPriceEffectiveDate().isAfter(today)) {
            blocking.add(new Issue("PRICE_NOT_YET_EFFECTIVE",
                    String.format("价格 %s 起生效, 生效前不能选定", price.getPriceEffectiveDate())));
        }

        // ---- 供应商 / 供应关系 ----
        if (supplier == null) {
            blocking.add(new Issue("SUPPLIER_NOT_FOUND", "供应商不存在或不属于本厂"));
        } else if (!Boolean.TRUE.equals(supplier.getIsActive())) {
            blocking.add(new Issue("SUPPLIER_INACTIVE", "供应商已暂停合作"));
        }
        SupplierMaterial relation = facts.relationsBySupplierId().get(price.getSupplierId());
        if (relation == null || !Boolean.TRUE.equals(relation.getActive())) {
            blocking.add(new Issue("SUPPLIER_MATERIAL_RELATION_MISSING",
                    "该供应商未维护本物料的可用供应关系, 请先在供应商详情的「供应原料」中建立"));
        }

        // ---- 起订量: 本次报价优先, 其次供应关系 ----
        if (price.getMinOrderQuantity() != null) {
            row.setMinOrderQuantity(price.getMinOrderQuantity());
            row.setMinOrderQuantitySource(SOURCE_QUOTE);
            row.setMinOrderQuantityUnit(unit);
        } else if (relation != null && relation.getMinOrderQuantity() != null) {
            row.setMinOrderQuantity(relation.getMinOrderQuantity());
            row.setMinOrderQuantitySource(SOURCE_SUPPLIER_MATERIAL);
            row.setMinOrderQuantityUnit(relation.getPurchaseUnit());
        }
        if (row.getMinOrderQuantity() != null) {
            if (row.getMinOrderQuantityUnit() != null && sameText(row.getMinOrderQuantityUnit(), inquiry.getUnit())) {
                if (inquiry.getQuantity() != null && inquiry.getQuantity().compareTo(row.getMinOrderQuantity()) < 0) {
                    blocking.add(new Issue("BELOW_MOQ", String.format(
                            "询价数量 %s %s 低于起订量 %s %s",
                            plain(inquiry.getQuantity()), inquiry.getUnit(),
                            plain(row.getMinOrderQuantity()), row.getMinOrderQuantityUnit())));
                }
            } else {
                warnings.add(new Issue("MOQ_UNIT_DIFFERENT", String.format(
                        "起订量 %s %s 的单位与询价单位「%s」不同, 无法判断是否满足",
                        plain(row.getMinOrderQuantity()),
                        row.getMinOrderQuantityUnit() == null ? "(单位未知)" : row.getMinOrderQuantityUnit(),
                        inquiry.getUnit())));
            }
        }

        // ---- 交期: 本次报价优先, 其次供应关系 ----
        if (price.getDeliveryDays() != null) {
            row.setLeadTimeDays(price.getDeliveryDays());
            row.setLeadTimeSource(SOURCE_QUOTE);
        } else if (relation != null && relation.getLeadTimeDays() != null) {
            row.setLeadTimeDays(relation.getLeadTimeDays());
            row.setLeadTimeSource(SOURCE_SUPPLIER_MATERIAL);
        }
        if (row.getLeadTimeDays() == null) {
            warnings.add(new Issue("LEAD_TIME_UNKNOWN", "报价与供应关系都没有交期"));
        } else {
            row.setExpectedArrivalDate(today.plusDays(row.getLeadTimeDays()));
            if (inquiry.getRequiredDate() != null && row.getExpectedArrivalDate().isAfter(inquiry.getRequiredDate())) {
                warnings.add(new Issue("LEAD_TIME_LATE", String.format(
                        "今天下单预计 %s 到货, 晚于需求日 %s", row.getExpectedArrivalDate(), inquiry.getRequiredDate())));
            }
        }

        // ---- 采购包装规格: 供应关系配了规格时必须能按报价单位唯一确定 ----
        if (relation != null && unit != null) {
            List<SupplierMaterialPurchaseSpec> specs =
                    facts.activeSpecsByRelationId().getOrDefault(relation.getId(), List.of());
            if (!specs.isEmpty()) {
                List<SupplierMaterialPurchaseSpec> matching = specs.stream()
                        .filter(s -> sameText(s.getPurchasePackageUnit(), unit))
                        .collect(Collectors.toList());
                if (matching.size() == 1) {
                    row.setPurchasePackagingSpecId(matching.get(0).getId());
                } else {
                    blocking.add(new Issue("PURCHASE_SPEC_UNRESOLVED", String.format(
                            "供应关系有 %d 个采购包装规格与单位「%s」匹配, 无法确定下单规格",
                            matching.size(), unit)));
                }
            }
        }

        row.setSelectable(blocking.isEmpty());
        return row;
    }

    private static boolean sameText(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return a.trim().replaceAll("\\s+", " ").equalsIgnoreCase(b.trim().replaceAll("\\s+", " "));
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
