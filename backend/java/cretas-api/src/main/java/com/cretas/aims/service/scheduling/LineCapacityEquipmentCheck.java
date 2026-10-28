package com.cretas.aims.service.scheduling;

import com.cretas.aims.entity.FactoryEquipment;
import com.cretas.aims.entity.ProductEquipmentRequirement;
import com.cretas.aims.entity.ProductionLine;
import com.cretas.aims.entity.ProductionLineCapacityConfig;
import com.cretas.aims.entity.ProductionLineCapacityConfig.CapacityUnit;
import com.cretas.aims.repository.EquipmentRepository;
import com.cretas.aims.repository.ProductEquipmentRequirementRepository;
import com.cretas.aims.repository.ProductionLineCapacityConfigRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.service.ImpactAnalysisService.ResourceCheckResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * S4 插单可行性的产能与设备口径: 只读对话建厂 / 工厂配置写下的配置, 结果三态
 * READY (核对过且够) / MISSING (缺配置, 未验证) / CONFLICT (核对过且不够)。
 *
 * <p>不换算单位、不套默认产能 (旧的 hourly_capacity 500 kg 不算读数)、不靠字符串猜设备。
 * 设备的未来停机 / 占用窗口还没有建模, READY 的设备只说明「配置了且当前不在检修/报废」。
 */
@Service
@RequiredArgsConstructor
public class LineCapacityEquipmentCheck {

    public static final String READY = "READY";
    public static final String MISSING = "MISSING";
    public static final String CONFLICT = "CONFLICT";

    private static final Set<String> EQUIPMENT_DOWN = Set.of("maintenance", "scrapped");
    private static final Set<String> EQUIPMENT_UP = Set.of("idle", "running");

    private final ProductionLineCapacityConfigRepository capacityConfigs;
    private final ProductEquipmentRequirementRepository equipmentRequirements;
    private final ProductionLineRepository productionLines;
    private final EquipmentRepository equipment;

    /** READY / MISSING / CONFLICT, read off a check result built by this class. */
    public static String status(ResourceCheckResult r) {
        if (!r.isVerified()) {
            return MISSING;
        }
        return r.isAvailable() ? READY : CONFLICT;
    }

    /**
     * 在 [start, end) 这个时段里, 单条产线最多能做多少 (订单只排在一条线上, 所以取单线最大值)。
     *
     * @param unit 订单数量的单位 (PIECE / KG / BATCH, 大小写不敏感); 为空或不认识 ⇒ MISSING
     * @param preferredLineId 指定了产线就只看这条线
     */
    @Transactional(readOnly = true)
    public ResourceCheckResult checkCapacity(String factoryId, String productTypeId, BigDecimal quantity, String unit,
                                             String preferredLineId, LocalDateTime start, LocalDateTime end) {
        if (productTypeId == null || quantity == null) {
            return ResourceCheckResult.unverified("CAPACITY", "产能未验证: 缺少产品或插单数量", quantity);
        }
        CapacityUnit orderUnit = parseUnit(unit);
        if (orderUnit == null) {
            return ResourceCheckResult.unverified("CAPACITY",
                    "产能未验证: 插单数量没有单位 (件 PIECE / 千克 KG / 批 BATCH)", quantity);
        }
        BigDecimal hours = BigDecimal.valueOf(Duration.between(start, end).toMinutes())
                .divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);

        Map<String, ProductionLine> lines = productionLines.findByFactoryIdAndDeletedAtIsNull(factoryId).stream()
                .collect(Collectors.toMap(ProductionLine::getId, Function.identity()));

        // 每条线取一行: 产品专用行优先于通用行
        Map<String, ProductionLineCapacityConfig> perLine = new LinkedHashMap<>();
        for (ProductionLineCapacityConfig c : capacityConfigs.findEffectiveForProduct(factoryId, productTypeId, start)) {
            if (preferredLineId != null && !preferredLineId.equals(c.getProductionLineId())) {
                continue;
            }
            perLine.merge(c.getProductionLineId(), c, (a, b) -> a.getProductTypeId() != null ? a : b);
        }

        List<String> otherUnits = new ArrayList<>();
        List<String> lineDown = new ArrayList<>();
        ProductionLineCapacityConfig best = null;
        for (ProductionLineCapacityConfig c : perLine.values()) {
            ProductionLine line = lines.get(c.getProductionLineId());
            String name = line == null ? c.getProductionLineId() : line.getName();
            if (c.getCapacityUnit() != orderUnit) {
                otherUnits.add(name + " 配的是 " + unitLabel(c.getCapacityUnit()) + "/小时");
                continue;
            }
            if (line == null) {
                continue; // 产线已删除: 配置作废, 不算读数
            }
            if (line.getStatus() != ProductionLine.LineStatus.active) {
                lineDown.add(name + " (" + line.getStatus() + ")");
                continue;
            }
            if (best == null || c.getCapacityPerHour().compareTo(best.getCapacityPerHour()) > 0) {
                best = c;
            }
        }

        if (best == null) {
            if (!lineDown.isEmpty()) {
                return ResourceCheckResult.unavailable("CAPACITY",
                        "产能不足: 配了" + unitLabel(orderUnit) + "产能的产线都不在运行: " + String.join("、", lineDown),
                        BigDecimal.ZERO, quantity);
            }
            String scope = preferredLineId == null ? "本厂没有产线" : "指定产线";
            String detail = otherUnits.isEmpty() ? "" : " (" + String.join("; ", otherUnits) + ")";
            return ResourceCheckResult.unverified("CAPACITY",
                    "产能未验证: " + scope + "配置该产品按" + unitLabel(orderUnit) + "计的每小时产能" + detail
                            + ", 请在工厂配置或对话建厂里补上", quantity);
        }

        BigDecimal canDo = best.getCapacityPerHour().multiply(hours).setScale(3, RoundingMode.HALF_UP);
        String lineName = lines.get(best.getProductionLineId()).getName();
        if (canDo.compareTo(quantity) >= 0) {
            return ResourceCheckResult.available("CAPACITY", canDo, quantity,
                    lineName + " 每小时 " + plain(best.getCapacityPerHour()) + " " + unitLabel(orderUnit)
                            + ", 本时段最多 " + plain(canDo) + " " + unitLabel(orderUnit));
        }
        return ResourceCheckResult.unavailable("CAPACITY",
                "产能不足: 需要 " + plain(quantity) + " " + unitLabel(orderUnit) + ", 本时段单线最多 "
                        + plain(canDo) + " " + unitLabel(orderUnit) + " (" + lineName + ")",
                canDo, quantity);
    }

    /** 做这个产品要用到的设备是否都配置了、属于本厂、且不在检修/报废。 */
    @Transactional(readOnly = true)
    public ResourceCheckResult checkEquipment(String factoryId, String productTypeId) {
        if (productTypeId == null) {
            return ResourceCheckResult.unverified("EQUIPMENT", "设备未验证: 缺少产品", BigDecimal.ONE);
        }
        List<Long> ids = equipmentRequirements.findByFactoryIdAndProductTypeId(factoryId, productTypeId).stream()
                .map(ProductEquipmentRequirement::getEquipmentId).distinct().sorted().toList();
        BigDecimal required = BigDecimal.valueOf(ids.size());
        if (ids.isEmpty()) {
            return ResourceCheckResult.unverified("EQUIPMENT",
                    "设备未验证: 该产品没有配置所需设备, 请在工厂配置或对话建厂里补上", BigDecimal.ONE);
        }
        Map<Long, FactoryEquipment> found = equipment.findByIdIn(ids).stream()
                .filter(e -> factoryId.equals(e.getFactoryId()))
                .collect(Collectors.toMap(FactoryEquipment::getId, Function.identity()));

        List<String> gone = new ArrayList<>();
        List<String> down = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        List<String> up = new ArrayList<>();
        for (Long id : ids) {
            FactoryEquipment e = found.get(id);
            if (e == null) {
                gone.add("#" + id);
                continue;
            }
            String st = e.getStatus() == null ? "" : e.getStatus().toLowerCase(Locale.ROOT);
            String label = e.getEquipmentName() + " (" + e.getEquipmentCode() + ")";
            if (EQUIPMENT_DOWN.contains(st)) {
                down.add(label + " " + st);
            } else if (EQUIPMENT_UP.contains(st)) {
                up.add(label);
            } else {
                unknown.add(label + " 状态 " + (st.isEmpty() ? "空" : st));
            }
        }
        if (!down.isEmpty()) {
            return ResourceCheckResult.unavailable("EQUIPMENT", "设备不可用: " + String.join("、", down),
                    BigDecimal.valueOf(up.size()), required);
        }
        if (!gone.isEmpty() || !unknown.isEmpty()) {
            List<String> why = new ArrayList<>();
            if (!gone.isEmpty()) {
                why.add("配置的设备在本厂找不到 " + String.join("、", gone));
            }
            if (!unknown.isEmpty()) {
                why.add(String.join("、", unknown));
            }
            return ResourceCheckResult.unverified("EQUIPMENT", "设备未验证: " + String.join("; ", why), required);
        }
        return ResourceCheckResult.available("EQUIPMENT", required, required,
                "所需设备 " + String.join("、", up) + " 均不在检修/报废 (未核对设备排程)");
    }

    public static CapacityUnit parseUnit(String unit) {
        if (unit == null || unit.isBlank()) {
            return null;
        }
        try {
            return CapacityUnit.valueOf(unit.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static String unitLabel(CapacityUnit u) {
        return switch (u) {
            case PIECE -> "件";
            case KG -> "kg";
            case BATCH -> "批";
        };
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
