package com.cretas.aims.ai.tool.impl.scheduling;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.impl.scheduling.RushOrderRequestParser.SaidQuantity;
import com.cretas.aims.dto.scheduling.GetInsertSlotsRequest;
import com.cretas.aims.entity.InsertSlot;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.repository.InsertSlotRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.UrgentInsertService;
import com.cretas.aims.service.scheduling.UrgentInsertMaterialAssessor;
import com.cretas.aims.service.scheduling.UrgentInsertMaterialAssessor.Assessment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 急单影响分析 (数字计划员「周五要交 2 吨酱牛肉急单, 原料够不够, 产线排不排得开?」)。
 *
 * <p>依次答四项, 每项给数字和依据; 算不出来的那项明确说「算不出来, 缺 X」, ⛔ 不编数:
 * <ol>
 *   <li>物料够不够: {@link UrgentInsertMaterialAssessor} 按当前生效配方展开, 对照可领库存 (在途不计入可用);</li>
 *   <li>产线和人手: 交期前的插单时段, 逐个走插单影响分析的只读 GET 口径 ({@link UrgentInsertService#analyzeInsertImpact});
 *       人手按工厂纪律口径「有排班但人不够不硬拦」, 只提示;</li>
 *   <li>要挪哪几张原单、各晚多久: 同一次影响分析的链式延误 (受影响计划 + 延误分钟);</li>
 *   <li>方案草案: 只生成文字, 不建计划、不占时段、不建预览 —— 真正插单仍走「紧急插单」页的预览 + 确认 (会重新核对)。</li>
 * </ol>
 *
 * <p>只读: 不调 {@code getAvailableSlots} (它会先清理过期时段, 是写), 直接读 available 时段再在内存里筛;
 * 不调 {@code createInsertPreview} (建预览是写)。订单单位按产品自带单位 (S4 口径), 认不出的单位不换算。
 */
@Slf4j
@Component
public class RushOrderImpactTool extends AbstractBusinessTool {

    static final String TOOL_NAME = "scheduling_rush_order_impact";
    /** 最多细算几个候选时段 (每个都要跑一次链式影响分析)。 */
    static final int MAX_SLOTS_EVALUATED = 3;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("M月d日 HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Autowired
    @Lazy
    private ProductTypeRepository productTypeRepository;

    @Autowired
    @Lazy
    private UrgentInsertMaterialAssessor materialAssessor;

    @Autowired
    @Lazy
    private InsertSlotRepository insertSlotRepository;

    @Autowired
    @Lazy
    private UrgentInsertService urgentInsertService;

    @Override
    public String getToolName() {
        return "scheduling_rush_order_impact";
    }

    @Override
    public String getDescription() {
        return "急单 / 插单影响分析 (只读): 给定产品、数量 (按产品自带单位) 和交期, 依次回答 ① 原料够不够、缺哪几样缺多少;"
                + " ② 交期前产线和人手排不排得开; ③ 要挪哪几张原生产计划、各晚多久; ④ 一份待负责人确认的文字方案草案。"
                + "适用: '周五要交 2 吨酱牛肉急单, 原料够不够, 产线排不排得开' / '插一单 500 公斤卤鸭能不能排' / '急单齐套吗'。"
                + "不建计划、不占时段、不建预览; 算不出来的项会说缺什么。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("productName", Map.of("type", "string",
                "description", "急单的产品名称, 如 '酱牛肉'。只填用户说的名称, 不要猜。"));
        properties.put("quantity", Map.of("type", "number",
                "description", "急单数量 (用户说的数字, 如 2)。"));
        properties.put("quantityUnit", Map.of("type", "string",
                "description", "用户说的数量单位原文, 如 '吨' '公斤' '件'; 没说就不填。"));
        properties.put("deadline", Map.of("type", "string",
                "description", "交期日期 yyyy-MM-dd; 用户说 '周五' 时换算成最近的周五。"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of();
    }

    protected LocalDateTime now() {
        return LocalDateTime.now();
    }

    // ------------------------------------------------------------------ 执行

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String said = firstNonBlank(getString(params, "originalUserInput"), getString(params, "userInput"));
        LocalDateTime now = now();

        // 产品
        ProductLookup product = lookupProduct(factoryId, getString(params, "productName"), said);
        // 数量 (用户说的) → 产品自带单位
        SaidQuantity saidQty = saidQuantity(params, said);
        BigDecimal qty = null;
        String qtyGap = null;
        if (saidQty == null) {
            qtyGap = "数量 (要做多少)";
        } else if (product.found() != null) {
            qty = RushOrderRequestParser.toProductUnit(saidQty, product.found().getUnit()).orElse(null);
            if (qty == null) {
                qtyGap = product.found().getUnit() == null || product.found().getUnit().isBlank()
                        ? "产品「" + product.found().getName() + "」没有登记计量单位, 无法把 " + said(saidQty) + " 对上产品单位"
                        : "单位换算: 你说的是「" + said(saidQty) + "」, 产品「" + product.found().getName() + "」按「"
                        + product.found().getUnit() + "」计, 这两个单位之间没有确定的换算";
            }
        }
        // 交期
        LocalDate deadline = RushOrderRequestParser.deadline(params.get("deadline"), said, now.toLocalDate()).orElse(null);
        String deadlineGap = deadline == null ? "交期 (哪天要交)"
                : deadline.isBefore(now.toLocalDate()) ? "有效交期 (" + deadline + " 已经过去)" : null;

        List<Map<String, Object>> sections = new ArrayList<>();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productName", product.found() == null ? null : product.found().getName());
        data.put("productTypeId", product.found() == null ? null : product.found().getId());
        data.put("productUnit", product.found() == null ? null : product.found().getUnit());
        data.put("saidQuantity", saidQty == null ? null : said(saidQty));
        data.put("quantityInProductUnit", qty);
        data.put("deadline", deadline == null ? null : deadline.toString());

        // ① 物料
        Assessment material = null;
        if (product.gap() != null) {
            sections.add(section("MATERIAL", "UNVERIFIED", "算不出来，缺" + product.gap() + "。", null));
        } else if (qtyGap != null) {
            sections.add(section("MATERIAL", "UNVERIFIED", "算不出来，缺" + qtyGap + "。", null));
        } else {
            material = materialAssessor.assess(factoryId, product.found().getId(), qty);
            sections.add(materialSection(material, product.found(), qty));
        }

        // ② ③ 产线人手 + 挪单
        SlotOutcome best = null;
        List<SlotOutcome> evaluated = List.of();
        int candidateCount = 0;
        String lineGap = product.gap() != null ? product.gap() : qtyGap != null ? qtyGap : deadlineGap;
        if (lineGap == null) {
            List<InsertSlot> candidates = candidateSlots(factoryId, deadline, now);
            candidateCount = candidates.size();
            if (candidates.isEmpty()) {
                lineGap = "交期 " + deadline + " 前的可插单时段 (排产里没有空闲 / 未过期的插单时段, 需先在「紧急插单」生成时段)";
            } else {
                evaluated = evaluate(factoryId, candidates, product.found(), qty, deadline);
                best = pickBest(evaluated);
                if (best == null) {
                    lineGap = "候选时段的影响分析结果 (" + evaluated.size() + " 个时段都没算出来: "
                            + evaluated.stream().map(SlotOutcome::error).filter(e -> e != null).distinct()
                            .reduce((a, b) -> a + "; " + b).orElse("原因未知") + ")";
                }
            }
        }
        if (lineGap != null) {
            sections.add(section("LINE_AND_LABOR", "UNVERIFIED", "算不出来，缺" + lineGap + "。", null));
            sections.add(section("MOVED_ORDERS", "UNVERIFIED", "算不出来，缺" + lineGap + "。", null));
        } else {
            sections.add(lineSection(best, evaluated, candidateCount));
            sections.add(movedSection(best));
        }
        data.put("candidateSlotCount", candidateCount);
        data.put("evaluatedSlots", evaluated.stream().map(SlotOutcome::summary).toList());

        // ④ 方案草案 (只文字)
        sections.add(draftSection(product.found(), saidQty, qty, deadline, material, best, sections));

        data.put("sections", sections);
        data.put("writesPerformed", false);
        data.put("basis", "原料按当前生效配方展开对照可领库存 (在途不计入可用); 产线 / 人手 / 挪单取交期前插单时段的只读影响分析;"
                + " 订单单位按产品自带单位; 人手不够按工厂纪律口径只提示不硬拦。本次没有建计划、占时段或建预览。");
        return buildSimpleResult(message(sections), data);
    }

    // ------------------------------------------------------------------ 产品

    record ProductLookup(ProductType found, String gap) {
    }

    private ProductLookup lookupProduct(String factoryId, String productName, String said) {
        List<ProductType> products = productTypeRepository.findVisibleByFactoryIdAndIsActiveTrue(factoryId);
        if (products == null) products = List.of();
        String name = productName == null ? null : productName.strip();
        if (name != null && !name.isEmpty()) {
            List<ProductType> exact = products.stream().filter(p -> name.equals(p.getName())).toList();
            if (exact.size() == 1) return new ProductLookup(exact.get(0), null);
            List<ProductType> contains = products.stream()
                    .filter(p -> p.getName() != null && (p.getName().contains(name) || name.contains(p.getName())))
                    .toList();
            if (contains.size() == 1) return new ProductLookup(contains.get(0), null);
            if (contains.size() > 1) return ambiguous(name, contains);
        }
        if (said != null && !said.isBlank()) {
            // 原话里出现的产品名, 取最长的那个 (「五香酱牛肉」优先于「酱牛肉」)
            List<ProductType> mentioned = products.stream()
                    .filter(p -> p.getName() != null && p.getName().length() >= 2 && said.contains(p.getName()))
                    .toList();
            int longest = mentioned.stream().mapToInt(p -> p.getName().length()).max().orElse(0);
            List<ProductType> top = mentioned.stream().filter(p -> p.getName().length() == longest).toList();
            if (top.size() == 1) return new ProductLookup(top.get(0), null);
            if (top.size() > 1) return ambiguous(top.get(0).getName(), top);
        }
        String what = name != null && !name.isEmpty() ? "本厂产品里叫「" + name + "」的产品 (没找到在用的同名产品)"
                : "产品 (没说要做哪个产品, 或说的名称不在本厂在用产品里)";
        return new ProductLookup(null, what);
    }

    private static ProductLookup ambiguous(String name, Collection<ProductType> hits) {
        return new ProductLookup(null, "唯一的产品: 「" + name + "」对上了 " + hits.size() + " 个产品 ("
                + hits.stream().map(ProductType::getName).limit(5).reduce((a, b) -> a + "、" + b).orElse("") + "), 请说全名");
    }

    private static SaidQuantity saidQuantity(Map<String, Object> params, String said) {
        BigDecimal v = RushOrderRequestParser.number(params.get("quantity"));
        if (v != null && v.signum() > 0) {
            Object unit = params.get("quantityUnit");
            String u = unit == null || unit.toString().isBlank() ? null : unit.toString().strip();
            if (u == null) {
                // 模型给了数没给单位: 原话里同一个数后面跟着单位就用它
                Optional<SaidQuantity> fromText = RushOrderRequestParser.quantityFromText(said);
                if (fromText.isPresent() && fromText.get().value().compareTo(v) == 0) return fromText.get();
            }
            return new SaidQuantity(v, u);
        }
        return RushOrderRequestParser.quantityFromText(said).orElse(null);
    }

    // ------------------------------------------------------------------ ① 物料

    private static Map<String, Object> materialSection(Assessment a, ProductType product, BigDecimal qty) {
        String basis = a.bomRecipeId() == null ? null
                : "配方 " + a.bomRecipeId() + " 第 " + a.bomVersion() + " 版, " + product.getName() + " "
                + plain(qty) + unit(product.getUnit()) + "; 可领库存 = 车间 / 物流 / 原料仓可领量, 在途不计入";
        return switch (a.status()) {
            case OK -> section("MATERIAL", "ENOUGH",
                    "够。按生效配方展开 " + plain(qty) + unit(product.getUnit()) + " " + product.getName()
                            + " 的全部原料, 可领库存都覆盖需求, 不缺料。", basis);
            case SHORT -> {
                List<Map<String, Object>> lines = new ArrayList<>();
                StringBuilder sb = new StringBuilder("不够，缺 ").append(a.lines().size()).append(" 样：");
                int i = 0;
                for (UrgentInsertMaterialAssessor.Line l : a.lines()) {
                    sb.append("\n  ").append(++i).append(". ").append(l.materialName());
                    if (l.shortfall() == null) {
                        sb.append(" 不足 (当前账号无配方完整查看权, 不显示数量)");
                    } else {
                        sb.append(" 缺 ").append(plain(l.shortfall())).append(unit(l.unit()))
                                .append("（需要 ").append(plain(l.required())).append(unit(l.unit()))
                                .append("，可领库存 ").append(plain(l.available())).append(unit(l.unit())).append("）");
                        if (l.onOrder() != null && l.onOrder().signum() > 0) {
                            sb.append("，在途未到 ").append(plain(l.onOrder())).append(unit(l.unit()))
                                    .append("，到货后仍缺 ").append(plain(l.gapAfterOnOrder())).append(unit(l.unit()));
                        } else {
                            sb.append("，没有在途采购");
                        }
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("materialName", l.materialName());
                    row.put("unit", l.unit());
                    row.put("required", l.required());
                    row.put("available", l.available());
                    row.put("shortfall", l.shortfall());
                    row.put("onOrder", l.onOrder());
                    row.put("gapAfterOnOrder", l.gapAfterOnOrder());
                    lines.add(row);
                }
                Map<String, Object> s = section("MATERIAL", "SHORT", sb.toString(), basis);
                s.put("shortLines", lines);
                yield s;
            }
            default -> section("MATERIAL", "UNVERIFIED", "算不出来，缺可核对的数据：" + a.message() + "。", basis);
        };
    }

    // ------------------------------------------------------------------ ② ③ 时段

    private List<InsertSlot> candidateSlots(String factoryId, LocalDate deadline, LocalDateTime now) {
        LocalDateTime limit = deadline.plusDays(1).atStartOfDay();
        List<InsertSlot> all = insertSlotRepository.findByFactoryIdAndStatus(factoryId, "available");
        if (all == null) return List.of();
        return all.stream()
                .filter(s -> s.getProductionLineId() != null && !s.getProductionLineId().isBlank())
                .filter(s -> s.getStartTime() != null && s.getEndTime() != null)
                .filter(s -> s.getStartTime().isAfter(now) && !s.getEndTime().isAfter(limit))
                .sorted(Comparator.comparing((InsertSlot s) -> s.getRecommendScore() == null ? 0 : s.getRecommendScore())
                        .reversed().thenComparing(InsertSlot::getStartTime))
                .toList();
    }

    record SlotOutcome(InsertSlot slot, Map<String, Object> impact, String error) {
        boolean ok() {
            return impact != null;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> resources() {
            Object r = impact == null ? null : impact.get("resourceStatus");
            return r instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        }

        boolean lineFits() {
            Map<String, Object> r = resources();
            return Boolean.TRUE.equals(r.get("capacityVerified")) && Boolean.TRUE.equals(r.get("capacityAvailable"))
                    && Boolean.TRUE.equals(r.get("equipmentVerified")) && Boolean.TRUE.equals(r.get("equipmentAvailable"));
        }

        int affected() {
            Object n = impact == null ? null : impact.get("affectedPlanCount");
            return n instanceof Number x ? x.intValue() : Integer.MAX_VALUE;
        }

        int maxDelay() {
            Object d = impact == null ? null : impact.get("chainImpactDetails");
            Object m = d instanceof Map<?, ?> map ? map.get("maxDelayMinutes") : null;
            return m instanceof Number x ? x.intValue() : Integer.MAX_VALUE;
        }

        Map<String, Object> summary() {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("slotId", slot.getId());
            s.put("productionLineName", slot.getProductionLineName());
            s.put("startTime", slot.getStartTime() == null ? null : slot.getStartTime().toString());
            s.put("endTime", slot.getEndTime() == null ? null : slot.getEndTime().toString());
            s.put("analyzed", ok());
            s.put("error", error);
            if (ok()) {
                s.put("lineFits", lineFits());
                s.put("affectedPlanCount", affected() == Integer.MAX_VALUE ? null : affected());
                s.put("feasibilityStatus", impact.get("feasibilityStatus"));
            }
            return s;
        }
    }

    private List<SlotOutcome> evaluate(String factoryId, List<InsertSlot> candidates, ProductType product,
                                       BigDecimal qty, LocalDate deadline) {
        String capacityUnit = capacityUnitForProduct(product.getUnit());
        List<SlotOutcome> out = new ArrayList<>();
        for (InsertSlot slot : candidates.subList(0, Math.min(MAX_SLOTS_EVALUATED, candidates.size()))) {
            GetInsertSlotsRequest req = GetInsertSlotsRequest.builder()
                    .productTypeId(product.getId())
                    .requiredQuantity(qty)
                    .quantityUnit(capacityUnit)
                    .deadline(deadline.atTime(23, 59, 59))
                    .urgencyLevel("urgent")
                    .build();
            try {
                out.add(new SlotOutcome(slot, urgentInsertService.analyzeInsertImpact(factoryId, slot.getId(), req), null));
            } catch (RuntimeException e) {
                log.warn("[{}] 时段影响分析失败: factoryId={}, slotId={}, reason={}", TOOL_NAME, factoryId, slot.getId(),
                        e.getMessage());
                out.add(new SlotOutcome(slot, null, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }
        return out;
    }

    /** 产线排得下的优先, 其次挪单少、最大延误小; 都没算出来返回 null。 */
    static SlotOutcome pickBest(List<SlotOutcome> evaluated) {
        return evaluated.stream().filter(SlotOutcome::ok)
                .min(Comparator.comparing((SlotOutcome o) -> o.lineFits() ? 0 : 1)
                        .thenComparingInt(SlotOutcome::affected)
                        .thenComparingInt(SlotOutcome::maxDelay))
                .orElse(null);
    }

    /** 与插单服务同一张表: 产品单位 → 产能单位; 认不出返回 null (产能按未验证处理, 不套默认)。 */
    static String capacityUnitForProduct(String productUnit) {
        if (productUnit == null) return null;
        return switch (productUnit.trim().toLowerCase(Locale.ROOT)) {
            case "件", "个", "piece" -> "PIECE";
            case "kg", "公斤", "千克" -> "KG";
            case "批", "batch" -> "BATCH";
            default -> null;
        };
    }

    private static Map<String, Object> lineSection(SlotOutcome best, List<SlotOutcome> evaluated, int candidateCount) {
        Map<String, Object> r = best.resources();
        InsertSlot slot = best.slot();
        StringBuilder sb = new StringBuilder();
        boolean capVerified = Boolean.TRUE.equals(r.get("capacityVerified"));
        boolean capOk = capVerified && Boolean.TRUE.equals(r.get("capacityAvailable"));
        boolean eqVerified = Boolean.TRUE.equals(r.get("equipmentVerified"));
        boolean eqOk = eqVerified && Boolean.TRUE.equals(r.get("equipmentAvailable"));
        String status;
        if (capOk && eqOk) {
            status = "FITS";
            sb.append("排得开。");
        } else if ((capVerified && !capOk) || (eqVerified && !eqOk)) {
            status = "DOES_NOT_FIT";
            sb.append("排不开。");
        } else {
            status = "UNVERIFIED";
            sb.append("产线算不出来，缺产能 / 设备配置。");
        }
        sb.append("交期前有 ").append(candidateCount).append(" 个可插单时段, 细算了 ").append(evaluated.size())
                .append(" 个, 最合适的是「").append(nz(slot.getProductionLineName(), slot.getProductionLineId())).append("」")
                .append(fmt(slot.getStartTime())).append(" – ").append(fmt(slot.getEndTime())).append("。");
        sb.append("\n  产能：").append(nz(r.get("capacityMessage"), capOk ? "够" : "未核对"));
        sb.append("\n  设备：").append(nz(r.get("equipmentMessage"), eqOk ? "就绪" : "未核对"));
        String workerStatus = String.valueOf(r.get("workerStatus"));
        sb.append("\n  人手：");
        switch (workerStatus) {
            case "READY" -> sb.append("够，需要 ").append(r.get("requiredWorkers")).append(" 人，可用 ")
                    .append(r.get("availableWorkers")).append(" 人");
            case "SHORTAGE" -> sb.append("不够，需要 ").append(r.get("requiredWorkers")).append(" 人，可用 ")
                    .append(r.get("availableWorkers")).append(" 人（按工厂纪律口径只提示、不硬拦，需负责人调人）");
            default -> sb.append("算不出来，缺排班 / 人员占用数据（").append(nz(r.get("workerMessage"), "没有排班数据"))
                    .append("）");
        }
        Map<String, Object> s = section("LINE_AND_LABOR", status, sb.toString(),
                "插单时段影响分析 (只读): 产能按产线配置同单位核对, 设备按显式配置, 人手按 HR 班次与任务占用");
        s.put("slotId", slot.getId());
        s.put("productionLineName", slot.getProductionLineName());
        s.put("workerStatus", workerStatus);
        s.put("requiredWorkers", r.get("requiredWorkers"));
        s.put("availableWorkers", r.get("availableWorkers"));
        return s;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> movedSection(SlotOutcome best) {
        Map<String, Object> impact = best.impact();
        Object plansRaw = impact.get("impactedPlans");
        Object count = impact.get("affectedPlanCount");
        if (impact.get("impactLevel") == null || !(count instanceof Number)) {
            return section("MOVED_ORDERS", "UNVERIFIED", "算不出来，缺完整的链式影响分析结果（影响等级 / 受影响计划数没有算出）。", null);
        }
        List<Object> plans = plansRaw instanceof List<?> l ? (List<Object>) l : List.of();
        if (((Number) count).intValue() == 0 || plans.isEmpty()) {
            return section("MOVED_ORDERS", "NONE", "不用挪原单：这个时段插进去，不推迟任何已排的生产计划。",
                    "链式影响分析 (插单时段与已排计划的时间冲突 + 级联顺延)");
        }
        StringBuilder sb = new StringBuilder("要挪 ").append(((Number) count).intValue()).append(" 张原单：");
        List<Map<String, Object>> rows = new ArrayList<>();
        int i = 0;
        for (Object o : plans) {
            Map<String, Object> p = o instanceof Map<?, ?> m ? (Map<String, Object>) m : beanToMap(o);
            Integer delay = p.get("delayMinutes") instanceof Number n ? n.intValue() : null;
            sb.append("\n  ").append(++i).append(". ").append(nz(p.get("planNumber"), "计划 " + p.get("planId")));
            if (p.get("productName") != null) sb.append("（").append(p.get("productName")).append("）");
            if (p.get("customerName") != null) sb.append("，客户 ").append(p.get("customerName"));
            sb.append("：晚 ").append(delay == null ? "未知" : minutes(delay));
            if (Boolean.TRUE.equals(p.get("exceedsDeadline"))) sb.append("，会超出它自己的交期");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("planNumber", p.get("planNumber"));
            row.put("productName", p.get("productName"));
            row.put("customerName", p.get("customerName"));
            row.put("delayMinutes", delay);
            row.put("exceedsDeadline", p.get("exceedsDeadline"));
            rows.add(row);
        }
        Object details = impact.get("chainImpactDetails");
        if (details instanceof Map<?, ?> d && d.get("maxDelayMinutes") instanceof Number max) {
            sb.append("\n  最长晚 ").append(minutes(max.intValue()));
        }
        Map<String, Object> s = section("MOVED_ORDERS", "MOVES", sb.toString(),
                "链式影响分析 (插单时段与已排计划的时间冲突 + 级联顺延)");
        s.put("movedPlans", rows);
        return s;
    }

    private static Map<String, Object> beanToMap(Object o) {
        if (o instanceof com.cretas.aims.dto.scheduling.AffectedPlanDTO p) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("planId", p.getPlanId());
            m.put("planNumber", p.getPlanNumber());
            m.put("productName", p.getProductName());
            m.put("customerName", p.getCustomerName());
            m.put("delayMinutes", p.getDelayMinutes());
            m.put("exceedsDeadline", p.getExceedsDeadline());
            return m;
        }
        return Map.of();
    }

    // ------------------------------------------------------------------ ④ 草案

    private static Map<String, Object> draftSection(ProductType product, SaidQuantity saidQty, BigDecimal qty,
                                                    LocalDate deadline, Assessment material, SlotOutcome best,
                                                    List<Map<String, Object>> sections) {
        StringBuilder sb = new StringBuilder("方案草案（待负责人确认，本次没有建计划、没有占时段、没有执行任何操作）：");
        sb.append("\n  急单：").append(product == null ? "产品未定" : product.getName()).append(" ")
                .append(qty != null ? plain(qty) + unit(product == null ? null : product.getUnit())
                        : saidQty != null ? said(saidQty) : "数量未定")
                .append("，交期 ").append(deadline == null ? "未定" : deadline.format(DAY)).append("。");
        if (best != null) {
            sb.append("\n  安排：插到「").append(nz(best.slot().getProductionLineName(), best.slot().getProductionLineId()))
                    .append("」").append(fmt(best.slot().getStartTime())).append(" – ").append(fmt(best.slot().getEndTime()))
                    .append("。");
        }
        List<String> todo = new ArrayList<>();
        if (material != null && material.status() == UrgentInsertMaterialAssessor.Status.SHORT) {
            todo.add("先补齐缺的 " + material.lines().size() + " 样原料（见第 1 项），到货前不能开工");
        }
        Object moved = sections.stream().filter(s -> "MOVED_ORDERS".equals(s.get("key"))).findFirst()
                .map(s -> s.get("movedPlans")).orElse(null);
        if (moved instanceof List<?> l && !l.isEmpty()) {
            todo.add("和受影响的 " + l.size() + " 张原单的负责人 / 客户确认顺延（见第 3 项）");
        }
        sections.stream().filter(s -> "LINE_AND_LABOR".equals(s.get("key")))
                .filter(s -> "SHORTAGE".equals(s.get("workerStatus")))
                .findFirst().ifPresent(s -> todo.add("人手不够，需负责人调人（不硬拦）"));
        for (Map<String, Object> s : sections) {
            if ("UNVERIFIED".equals(s.get("status"))) {
                todo.add("第 " + (sections.indexOf(s) + 1) + " 项还算不出来，先补上缺的数据");
            }
        }
        if (todo.isEmpty()) {
            sb.append("\n  需要确认：无阻塞项。");
        } else {
            sb.append("\n  需要确认：");
            for (int i = 0; i < todo.size(); i++) sb.append("\n    ").append(i + 1).append(") ").append(todo.get(i));
        }
        sb.append("\n  下一步：负责人确认后，由有排产写权限的人在「紧急插单」按此时段生成预览再确认，系统会按最新数据重新核对一遍。");
        Map<String, Object> s = section("DRAFT", "DRAFT", sb.toString(), "只生成文字, 不落任何业务写");
        s.put("blockingItems", todo);
        return s;
    }

    // ------------------------------------------------------------------ 文案

    private static final Map<String, String> TITLES = Map.of(
            "MATERIAL", "1. 物料够不够", "LINE_AND_LABOR", "2. 产线和人手排不排得开",
            "MOVED_ORDERS", "3. 要挪哪几张原单、各晚多久", "DRAFT", "4. 方案草案");

    private static Map<String, Object> section(String key, String status, String text, String basis) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("key", key);
        s.put("title", TITLES.get(key));
        s.put("status", status);
        s.put("text", text);
        s.put("basis", basis);
        return s;
    }

    private static String message(List<Map<String, Object>> sections) {
        StringBuilder sb = new StringBuilder("急单影响分析：");
        for (Map<String, Object> s : sections) {
            sb.append("\n\n").append(s.get("title")).append("\n").append(s.get("text"));
            if (s.get("basis") != null) sb.append("\n  依据：").append(s.get("basis"));
        }
        return sb.toString();
    }

    private static String minutes(int m) {
        if (m < 60) return m + " 分钟";
        int h = m / 60;
        int r = m % 60;
        return r == 0 ? h + " 小时" : h + " 小时 " + r + " 分钟";
    }

    private static String fmt(LocalDateTime t) {
        return t == null ? "?" : t.format(TIME);
    }

    private static String said(SaidQuantity q) {
        return plain(q.value()) + (q.unit() == null ? "" : q.unit());
    }

    private static String plain(BigDecimal v) {
        return v == null ? "?" : v.stripTrailingZeros().toPlainString();
    }

    private static String unit(String u) {
        return u == null ? "" : u;
    }

    private static String nz(Object v, String fallback) {
        return v == null || v.toString().isBlank() ? fallback : v.toString();
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    // ------------------------------------------------------------------ 声明

    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }

    @Override
    public ActionType getActionType() {
        return ActionType.READ;
    }

    @Override
    public RiskLevel getRiskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public boolean supportsPreview() {
        return false;
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public Set<String> getDomainTags() {
        return Set.of("scheduling", "production");
    }

    // 不声明权限码: 插单时段 / 影响在 App 排产页只按模块开关开放 (SchedulingController GET urgent-insert 无权限码),
    // AI 路径不比 App 更严 (GatedDataToolAiPathPermissionTest, gated-data-domains.tsv 的 insert-slots 行)。
}
