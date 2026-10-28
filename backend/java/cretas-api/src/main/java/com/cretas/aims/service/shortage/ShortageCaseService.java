package com.cretas.aims.service.shortage;

import com.cretas.aims.dto.orchestration.MaterialRequirement;
import com.cretas.aims.dto.orchestration.MaterialShortfall;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.entity.shortage.ShortageCase;
import com.cretas.aims.entity.shortage.ShortageCase.Stage;
import com.cretas.aims.entity.shortage.ShortageCaseEvent;
import com.cretas.aims.entity.shortage.ShortageCaseLine;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.shortage.ShortageCaseEventRepository;
import com.cretas.aims.repository.shortage.ShortageCaseLineRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.repository.shortage.ShortageSupplyQueryRepository;
import com.cretas.aims.service.shortage.dto.ShortageReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 缺料异常处理实例 (S4 L1 切片 1): 建立与按事实刷新。
 *
 * <p>一个销售订单的原料缺口 = 一个未关闭实例 (部分唯一索引兜底); 财务审核通过、入库、质检放行都只刷新它。
 * 阶段由事实算出 (缺口为 0 → GAP_RESOLVED; 每条缺口都有质检中 / 在途 → WAITING_SUPPLY;
 * 比上次少 → PARTIALLY_RESOLVED; 否则 DETECTED)。终态 (CANCELLED / MANUAL / CLOSED) 不再被刷新改动。
 * 输入没变就不写留痕, 所以同一事件来多少次都只有一条 CREATED + 实际变化次数条 REFRESHED。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShortageCaseService {

    public static final String ROLE_PROCUREMENT = "procurement_manager";
    public static final String ROLE_QUALITY = "quality_inspector";
    public static final String ROLE_WAREHOUSE = "warehouse_manager";
    public static final String ROLE_PRODUCTION = "production_manager";

    private final ShortageAnalysisService shortageAnalysisService;
    private final ShortageCaseRepository caseRepository;
    private final ShortageCaseLineRepository lineRepository;
    private final ShortageCaseEventRepository eventRepository;
    private final ShortageSupplyQueryRepository supplyQueries;
    private final SalesOrderRepository salesOrderRepository;

    /**
     * 按销售订单的当前事实刷新 (没有实例且有原料缺口才新建)。
     *
     * @return 刷新后的实例; 没有缺口也没有实例 / 客供料订单 / 订单不属于本厂 ⇒ empty
     */
    @Transactional
    public Optional<ShortageCase> refreshForSalesOrder(String factoryId, String salesOrderId, String reason) {
        var order = salesOrderRepository.findById(salesOrderId)
                .filter(o -> factoryId.equals(o.getFactoryId()))
                .orElse(null);
        if (order == null) {
            log.warn("[ShortageCase] 销售订单不存在或不属于本厂, 不建实例: factory={}, SO={}", factoryId, salesOrderId);
            return Optional.empty();
        }
        if (order.getMaterialSupplyMode() == MaterialSupplyMode.CUSTOMER_SUPPLIED) {
            return Optional.empty(); // 客供料不走公司采购, 与缺料报告同口径
        }

        ShortageReport report = shortageAnalysisService.analyzeForSalesOrder(factoryId, salesOrderId);
        List<Facts> facts = factsFrom(factoryId, report);

        ShortageCase existing = caseRepository
                .findOpenForUpdate(factoryId, ShortageCase.SourceType.SALES_ORDER, salesOrderId)
                .orElse(null);
        boolean anyGap = facts.stream().anyMatch(f -> f.gap.signum() > 0);
        if (existing == null && !anyGap) {
            return Optional.empty();
        }
        if (existing != null && existing.getStage().isTerminal()) {
            return Optional.of(existing);
        }
        return Optional.of(existing == null
                ? create(factoryId, salesOrderId, facts, reason)
                : refresh(existing, facts, reason));
    }

    /** 入库 / 质检放行: 本厂含这种物料的未关闭实例逐个刷新。 */
    @Transactional
    public int refreshForMaterial(String factoryId, String materialTypeId, String reason) {
        int n = 0;
        for (ShortageCase c : caseRepository.findOpenByFactoryIdAndMaterial(factoryId, materialTypeId)) {
            if (c.getSourceType() == ShortageCase.SourceType.SALES_ORDER) {
                refreshForSalesOrder(factoryId, c.getSourceId(), reason);
                n++;
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- internals

    /** 一种物料在当前时点的事实。 */
    record Facts(String materialTypeId, String materialName, String unit, BigDecimal required,
                 BigDecimal released, BigDecimal inspecting, BigDecimal onOrder, int onOrderUnitMismatch,
                 BigDecimal gap) {
    }

    private List<Facts> factsFrom(String factoryId, ShortageReport report) {
        Map<String, MaterialShortfall> shortByMaterial = new HashMap<>();
        if (report.getMaterialShortages() != null) {
            for (MaterialShortfall s : report.getMaterialShortages()) {
                shortByMaterial.put(s.getMaterialTypeId(), s);
            }
        }
        List<Facts> out = new ArrayList<>();
        if (report.getTotalRequired() == null) {
            return out;
        }
        for (MaterialRequirement r : report.getTotalRequired()) {
            if (r.getMaterialTypeId() == null) {
                continue;
            }
            BigDecimal required = nz(r.getRequiredQuantity());
            MaterialShortfall s = shortByMaterial.get(r.getMaterialTypeId());
            // 不在缺口清单里 = 已放行可用的够用 ⇒ 记成 required (只看对需求的覆盖, 不记总库存)
            BigDecimal released = s == null ? required : nz(s.getAvailableQuantity()).min(required).max(BigDecimal.ZERO);
            BigDecimal gap = required.subtract(released).max(BigDecimal.ZERO);
            String unit = blankToNull(r.getSourceUnit());

            BigDecimal inspecting = nz(supplyQueries.sumInspectingQuantity(factoryId, r.getMaterialTypeId()));
            BigDecimal onOrder = BigDecimal.ZERO;
            int mismatch = 0;
            for (Object[] row : supplyQueries.findOpenOrderRemaining(factoryId, r.getMaterialTypeId())) {
                String rowUnit = blankToNull(row[0] == null ? null : row[0].toString());
                BigDecimal remaining = row[1] instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(row[1]));
                if (unit != null && rowUnit != null && unit.equalsIgnoreCase(rowUnit)) {
                    onOrder = onOrder.add(remaining);
                } else {
                    mismatch++; // ⛔ 不换算: 单位不同 (或需求单位未知) 的采购行只计行数
                }
            }
            String name = s != null && s.getMaterialTypeName() != null ? s.getMaterialTypeName() : r.getMaterialTypeName();
            out.add(new Facts(r.getMaterialTypeId(), name, unit, required, released, inspecting, onOrder, mismatch, gap));
        }
        return out;
    }

    private ShortageCase create(String factoryId, String salesOrderId, List<Facts> facts, String reason) {
        LocalDateTime now = LocalDateTime.now();
        ShortageCase c = new ShortageCase();
        c.setFactoryId(factoryId);
        c.setSourceType(ShortageCase.SourceType.SALES_ORDER);
        c.setSourceId(salesOrderId);
        Stage stage = stageFrom(facts, null);
        c.setStage(stage);
        c.setOwnerRole(ownerFor(stage, facts));
        c.setGapLineCount(gapLines(facts));
        c.setInputFingerprint(fingerprint(facts));
        c.setLastRefreshedAt(now);
        c.setLastRefreshReason(reason);
        if (stage == Stage.GAP_RESOLVED) {
            c.setGapResolvedAt(now);
        }
        c = caseRepository.saveAndFlush(c);
        for (Facts f : facts) {
            lineRepository.save(lineFrom(new ShortageCaseLine(), c, f));
        }
        event(c, ShortageCaseEvent.EventType.CREATED, null, stage, reason, summary(facts));
        return c;
    }

    private ShortageCase refresh(ShortageCase c, List<Facts> facts, String reason) {
        String fp = fingerprint(facts);
        if (fp.equals(c.getInputFingerprint())) {
            return c; // 输入没变: 不写任何东西 (同一事件重复来是常态)
        }
        Map<String, ShortageCaseLine> lines = new LinkedHashMap<>();
        for (ShortageCaseLine l : lineRepository.findByCaseIdOrderByIdAsc(c.getId())) {
            lines.put(l.getMaterialTypeId(), l);
        }
        BigDecimal before = lines.values().stream().map(ShortageCaseLine::getGapQty).reduce(BigDecimal.ZERO, BigDecimal::add);
        for (Facts f : facts) {
            ShortageCaseLine l = lines.getOrDefault(f.materialTypeId, new ShortageCaseLine());
            lineRepository.save(lineFrom(l, c, f));
        }
        Stage from = c.getStage();
        Stage to = stageFrom(facts, before);
        LocalDateTime now = LocalDateTime.now();
        c.setStage(to);
        c.setOwnerRole(ownerFor(to, facts));
        c.setGapLineCount(gapLines(facts));
        c.setInputFingerprint(fp);
        c.setLastRefreshedAt(now);
        c.setLastRefreshReason(reason);
        c.setGapResolvedAt(to == Stage.GAP_RESOLVED ? (c.getGapResolvedAt() != null ? c.getGapResolvedAt() : now) : null);
        c = caseRepository.saveAndFlush(c);
        event(c, from == to ? ShortageCaseEvent.EventType.REFRESHED : ShortageCaseEvent.EventType.STAGE_CHANGED,
                from, to, reason, summary(facts));
        return c;
    }

    /** previousGap == null 表示新建 (没有「比上次少」这一说)。 */
    static Stage stageFrom(List<Facts> facts, BigDecimal previousGap) {
        List<Facts> gaps = facts.stream().filter(f -> f.gap.signum() > 0).toList();
        if (gaps.isEmpty()) {
            return Stage.GAP_RESOLVED;
        }
        boolean allCovered = gaps.stream().allMatch(f -> f.inspecting.add(f.onOrder).signum() > 0);
        if (allCovered) {
            return Stage.WAITING_SUPPLY;
        }
        BigDecimal now = gaps.stream().map(f -> f.gap).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (previousGap != null && now.compareTo(previousGap) < 0) {
            return Stage.PARTIALLY_RESOLVED;
        }
        return Stage.DETECTED;
    }

    /** 该谁处理: 有没人管的缺口 → 采购; 有质检中的 → 质检; 只差在途 → 仓库收货; 已解决 → 生产。 */
    static String ownerFor(Stage stage, List<Facts> facts) {
        if (stage == Stage.GAP_RESOLVED) {
            return ROLE_PRODUCTION;
        }
        List<Facts> gaps = facts.stream().filter(f -> f.gap.signum() > 0).toList();
        if (gaps.stream().anyMatch(f -> f.inspecting.add(f.onOrder).signum() == 0)) {
            return ROLE_PROCUREMENT;
        }
        if (gaps.stream().anyMatch(f -> f.inspecting.signum() > 0)) {
            return ROLE_QUALITY;
        }
        return ROLE_WAREHOUSE;
    }

    private static int gapLines(List<Facts> facts) {
        return (int) facts.stream().filter(f -> f.gap.signum() > 0).count();
    }

    private static ShortageCaseLine lineFrom(ShortageCaseLine l, ShortageCase c, Facts f) {
        l.setCaseId(c.getId());
        l.setFactoryId(c.getFactoryId());
        l.setMaterialTypeId(f.materialTypeId);
        l.setMaterialName(f.materialName);
        l.setUnit(f.unit);
        l.setRequiredQty(f.required);
        l.setReleasedQty(f.released);
        l.setInspectingQty(f.inspecting);
        l.setOnOrderQty(f.onOrder);
        l.setOnOrderUnitMismatchLines(f.onOrderUnitMismatch);
        l.setGapQty(f.gap);
        return l;
    }

    private void event(ShortageCase c, ShortageCaseEvent.EventType type, Stage from, Stage to, String reason, String detail) {
        ShortageCaseEvent e = new ShortageCaseEvent();
        e.setCaseId(c.getId());
        e.setFactoryId(c.getFactoryId());
        e.setEventType(type);
        e.setFromStage(from);
        e.setToStage(to);
        e.setTriggerReason(reason);
        e.setDetail(detail);
        e.setActorType("SYSTEM");
        eventRepository.save(e);
    }

    private static String summary(List<Facts> facts) {
        StringBuilder b = new StringBuilder();
        for (Facts f : facts) {
            if (f.gap.signum() > 0) {
                b.append(f.materialName != null ? f.materialName : f.materialTypeId)
                        .append(" 缺 ").append(f.gap.stripTrailingZeros().toPlainString())
                        .append(f.unit != null ? " " + f.unit : " (单位未知)").append("; ");
            }
        }
        return b.length() == 0 ? "缺口已全部覆盖" : b.toString().trim();
    }

    static String fingerprint(List<Facts> facts) {
        List<Facts> sorted = new ArrayList<>(facts);
        sorted.sort(Comparator.comparing(Facts::materialTypeId));
        StringBuilder b = new StringBuilder();
        for (Facts f : sorted) {
            b.append(f.materialTypeId).append('|').append(f.unit).append('|')
                    .append(f.required.stripTrailingZeros().toPlainString()).append('|')
                    .append(f.released.stripTrailingZeros().toPlainString()).append('|')
                    .append(f.inspecting.stripTrailingZeros().toPlainString()).append('|')
                    .append(f.onOrder.stripTrailingZeros().toPlainString()).append('|')
                    .append(f.onOrderUnitMismatch).append(';');
        }
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
