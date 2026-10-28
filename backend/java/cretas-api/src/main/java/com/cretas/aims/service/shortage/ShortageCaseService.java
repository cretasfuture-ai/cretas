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
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitDisplayNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
 *
 * <p>数量口径: 需求 / 已放行 / 缺口都取库存检查换算后的<b>库存单位</b> ({@link MaterialShortfall#getQuantityUnit}),
 * 质检中按批次 (同为库存单位), 在途只计与之等价的采购单位 (问单位契约, ⛔ 不比字符串, ⛔ 不换算)。
 * 配方单位 (如 g) 和库存单位 (如 kg) 不同时, ⛔ 不能拿配方量去减库存量。
 *
 * <p>事务: 两个入口都是 REQUIRES_NEW —— 由监听器在原业务提交之后调用时, 这里失败只回滚这次刷新,
 * 不会把放行 / 收货本身标成失败。
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
    private final UnitContractService unitContractService;

    /**
     * 按销售订单的当前事实刷新 (没有实例且有原料缺口才新建)。先锁住已有实例再读事实, 旧快照赢不了新快照。
     *
     * @return 刷新后的实例; 没有缺口也没有实例 / 客供料订单 / 订单不属于本厂 ⇒ empty
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ShortageCase> refreshForSalesOrder(String factoryId, String salesOrderId, String reason) {
        return refreshInCurrentTransaction(factoryId, salesOrderId, reason);
    }

    /** 入库 / 质检放行: 本厂含这种物料的未关闭实例逐个刷新。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int refreshForMaterial(String factoryId, String materialTypeId, String reason) {
        int n = 0;
        for (ShortageCase c : caseRepository.findOpenByFactoryIdAndMaterial(factoryId, materialTypeId)) {
            if (c.getSourceType() == ShortageCase.SourceType.SALES_ORDER) {
                refreshInCurrentTransaction(factoryId, c.getSourceId(), reason);
                n++;
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- internals

    private Optional<ShortageCase> refreshInCurrentTransaction(String factoryId, String salesOrderId, String reason) {
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

        ShortageCase existing = caseRepository
                .findOpenForUpdate(factoryId, ShortageCase.SourceType.SALES_ORDER, salesOrderId)
                .orElse(null);
        if (existing != null && existing.getStage().isTerminal()) {
            return Optional.of(existing);
        }
        Map<String, ShortageCaseLine> lines = new LinkedHashMap<>();
        if (existing != null) {
            for (ShortageCaseLine l : lineRepository.findByCaseIdOrderByIdAsc(existing.getId())) {
                lines.put(l.getMaterialTypeId(), l);
            }
        }

        ShortageReport report = shortageAnalysisService.analyzeForSalesOrder(factoryId, salesOrderId);
        List<Facts> facts = factsFrom(factoryId, report, lines);
        boolean anyGap = facts.stream().anyMatch(f -> f.gap.signum() > 0);
        if (existing == null && !anyGap) {
            return Optional.empty();
        }
        return Optional.of(existing == null
                ? create(factoryId, salesOrderId, facts, reason)
                : refresh(existing, lines, facts, reason));
    }

    /** 一种物料在当前时点的事实 (数量都在 {@link #unit} 下, 即库存单位)。 */
    record Facts(String materialTypeId, String materialName, String unit, BigDecimal required,
                 BigDecimal released, BigDecimal inspecting, BigDecimal onOrder, int onOrderUnitMismatch,
                 BigDecimal gap) {
    }

    /**
     * 缺口清单里的物料按库存检查的口径取数; 这次不缺、但实例里已有一行的物料 ⇒ 缺口记 0 (需求沿用那一行);
     * 订单已经不需要的物料 ⇒ 不在结果里 (调用方软删那一行)。从没缺过的物料不建行。
     */
    private List<Facts> factsFrom(String factoryId, ShortageReport report, Map<String, ShortageCaseLine> existing) {
        Map<String, MaterialShortfall> shortByMaterial = new LinkedHashMap<>();
        if (report.getMaterialShortages() != null) {
            for (MaterialShortfall s : report.getMaterialShortages()) {
                shortByMaterial.put(s.getMaterialTypeId(), s);
            }
        }
        Map<String, String> requiredNames = new LinkedHashMap<>();
        if (report.getTotalRequired() != null) {
            for (MaterialRequirement r : report.getTotalRequired()) {
                if (r.getMaterialTypeId() != null) {
                    requiredNames.put(r.getMaterialTypeId(), r.getMaterialTypeName());
                }
            }
        }
        List<Facts> out = new ArrayList<>();
        for (MaterialShortfall s : shortByMaterial.values()) {
            String unit = blankToNull(s.getQuantityUnit());
            BigDecimal required = nz(s.getRequiredQuantity());
            BigDecimal released = nz(s.getAvailableQuantity()).min(required).max(BigDecimal.ZERO);
            BigDecimal gap = nz(s.getShortfallQuantity()).max(BigDecimal.ZERO);
            String name = s.getMaterialTypeName() != null ? s.getMaterialTypeName() : requiredNames.get(s.getMaterialTypeId());
            out.add(withSupply(factoryId, s.getMaterialTypeId(), name, unit, required, released, gap));
        }
        for (Map.Entry<String, ShortageCaseLine> e : existing.entrySet()) {
            if (shortByMaterial.containsKey(e.getKey()) || !requiredNames.containsKey(e.getKey())) {
                continue; // 还缺 (上面已处理) / 订单已不需要 (不进结果 ⇒ 软删)
            }
            ShortageCaseLine l = e.getValue();
            out.add(withSupply(factoryId, l.getMaterialTypeId(), l.getMaterialName(), l.getUnit(),
                    l.getRequiredQty(), l.getRequiredQty(), BigDecimal.ZERO));
        }
        return out;
    }

    private Facts withSupply(String factoryId, String materialTypeId, String name, String unit, BigDecimal required,
                             BigDecimal released, BigDecimal gap) {
        BigDecimal inspecting = nz(supplyQueries.sumInspectingQuantity(factoryId, materialTypeId));
        BigDecimal onOrder = BigDecimal.ZERO;
        int mismatch = 0;
        for (Object[] row : supplyQueries.findOpenOrderRemaining(factoryId, materialTypeId)) {
            String rowUnit = blankToNull(row[0] == null ? null : row[0].toString());
            BigDecimal remaining = row[1] instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(row[1]));
            if (unit != null && rowUnit != null && unitContractService.areEquivalent(factoryId, unit, rowUnit)) {
                onOrder = onOrder.add(remaining);
            } else {
                mismatch++; // ⛔ 不换算: 单位不同 (或缺口单位未知) 的采购行只计行数
            }
        }
        return new Facts(materialTypeId, name, unit, required, released, inspecting, onOrder, mismatch, gap);
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

    private ShortageCase refresh(ShortageCase c, Map<String, ShortageCaseLine> lines, List<Facts> facts, String reason) {
        String fp = fingerprint(facts);
        if (fp.equals(c.getInputFingerprint())) {
            return c; // 输入没变: 不写任何东西 (同一事件重复来是常态)
        }
        BigDecimal before = lines.values().stream().map(ShortageCaseLine::getGapQty).reduce(BigDecimal.ZERO, BigDecimal::add);
        Set<String> present = new HashSet<>();
        for (Facts f : facts) {
            present.add(f.materialTypeId);
            ShortageCaseLine l = lines.getOrDefault(f.materialTypeId, new ShortageCaseLine());
            lineRepository.save(lineFrom(l, c, f));
        }
        for (ShortageCaseLine l : lines.values()) {
            if (!present.contains(l.getMaterialTypeId())) {
                lineRepository.delete(l); // 订单已不需要这种物料: 软删那一行, 不再留一个过期的「还缺」
            }
        }
        Stage from = c.getStage();
        Stage to = stageFrom(facts, before);
        BigDecimal now = facts.stream().map(f -> f.gap).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (to == Stage.DETECTED && from == Stage.PARTIALLY_RESOLVED && now.compareTo(before) <= 0) {
            to = from; // 只是在途变了、缺口没变大: 已经部分解决的不退回「待处理」
        }
        if (to == Stage.DETECTED && (from == Stage.PLANNED || from == Stage.EXECUTING)) {
            to = from; // 已经有人在处理 (定了方案 / 草稿已生成): 事实没进展时不退回「待处理」
        }
        LocalDateTime at = LocalDateTime.now();
        c.setStage(to);
        c.setOwnerRole(ownerFor(to, facts));
        c.setGapLineCount(gapLines(facts));
        c.setInputFingerprint(fp);
        c.setLastRefreshedAt(at);
        c.setLastRefreshReason(reason);
        c.setGapResolvedAt(to == Stage.GAP_RESOLVED ? (c.getGapResolvedAt() != null ? c.getGapResolvedAt() : at) : null);
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
                        .append(f.unit != null ? " " + UnitDisplayNames.display(f.unit) : " (单位未知)").append("; ");
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
                    .append(f.onOrderUnitMismatch).append('|')
                    .append(f.gap.stripTrailingZeros().toPlainString()).append(';');
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
