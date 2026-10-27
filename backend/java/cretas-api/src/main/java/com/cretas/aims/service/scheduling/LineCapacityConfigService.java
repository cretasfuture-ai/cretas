package com.cretas.aims.service.scheduling;

import com.cretas.aims.entity.FactoryEquipment;
import com.cretas.aims.entity.ProductEquipmentRequirement;
import com.cretas.aims.entity.ProductionLine;
import com.cretas.aims.entity.ProductionLineCapacityConfig;
import com.cretas.aims.entity.ProductionLineCapacityConfig.CapacityUnit;
import com.cretas.aims.entity.ProductionLineCapacityConfig.Source;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.EquipmentRepository;
import com.cretas.aims.repository.ProductEquipmentRequirementRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionLineCapacityConfigRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * S4 切片 2: 产线产能与产品所需设备的写入 (工厂配置页 / 对话建厂共用)。
 *
 * <p>改一条配置 = 软删现行行 + 插入立即生效的新行, 旧行留作审计。不支持预约未来生效:
 * 每条线 + 产品只有一条在用行, 而插单只认已生效的行, 预约会在生效前留下空窗。
 * 所有引用 (产线、产品、设备) 必须属于本厂, 否则 404, 不写任何东西。
 */
@Service
@RequiredArgsConstructor
public class LineCapacityConfigService {

    private final ProductionLineCapacityConfigRepository capacityConfigs;
    private final ProductEquipmentRequirementRepository equipmentRequirements;
    private final ProductionLineRepository productionLines;
    private final ProductTypeRepository productTypes;
    private final EquipmentRepository equipment;

    @Transactional(readOnly = true)
    public List<ProductionLineCapacityConfig> listCapacity(String factoryId) {
        return capacityConfigs.findByFactoryId(factoryId);
    }

    /**
     * 设定一条线 (可细到产品) 每小时产能; 同线同产品已有在用行时先软删它再插新行。
     *
     * @param productTypeId 为空 = 该线通用
     */
    @Transactional
    public ProductionLineCapacityConfig setCapacity(String factoryId, String productionLineId, String productTypeId,
                                                    BigDecimal capacityPerHour, String unit, Source source,
                                                    Long configuredBy) {
        if (configuredBy == null) {
            throw new BusinessException(401, "无法识别当前操作人, 未保存");
        }
        if (capacityPerHour == null || capacityPerHour.signum() <= 0) {
            throw new BusinessException(400, "每小时产能必须大于 0");
        }
        CapacityUnit capacityUnit = LineCapacityEquipmentCheck.parseUnit(unit);
        if (capacityUnit == null) {
            throw new BusinessException(400, "产能单位只能是 PIECE (件) / KG (千克) / BATCH (批)");
        }
        ProductionLine line = productionLines.findByIdAndFactoryIdAndDeletedAtIsNull(productionLineId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "产线不存在或不属于本厂: " + productionLineId));
        String product = productTypeId == null || productTypeId.isBlank() ? null : productTypeId;
        if (product != null && productTypes.findByIdAndFactoryId(product, factoryId).isEmpty()) {
            throw new BusinessException(404, "产品不存在或不属于本厂: " + product);
        }

        LocalDateTime now = LocalDateTime.now();
        // 先把旧行软删并立刻落库, 再插新行: 否则部分唯一索引会挡住新行 (Hibernate 默认先插后改)
        capacityConfigs.findByFactoryIdAndProductionLineIdAndProductTypeId(factoryId, line.getId(), product)
                .ifPresent(old -> {
                    old.setDeletedAt(now);
                    capacityConfigs.saveAndFlush(old);
                });

        ProductionLineCapacityConfig row = new ProductionLineCapacityConfig();
        row.setFactoryId(factoryId);
        row.setProductionLineId(line.getId());
        row.setProductTypeId(product);
        row.setCapacityPerHour(capacityPerHour);
        row.setCapacityUnit(capacityUnit);
        row.setSource(source);
        row.setConfiguredBy(configuredBy);
        row.setEffectiveFrom(now);
        return capacityConfigs.saveAndFlush(row);
    }

    /** 软删一条在用的产能配置 (本厂之外 / 不存在 ⇒ 404)。 */
    @Transactional
    public void retireCapacity(String factoryId, Long configId) {
        ProductionLineCapacityConfig row = capacityConfigs.findByIdAndFactoryId(configId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "产能配置不存在或不属于本厂: " + configId));
        row.setDeletedAt(LocalDateTime.now());
        capacityConfigs.saveAndFlush(row);
    }

    @Transactional(readOnly = true)
    public List<ProductEquipmentRequirement> listEquipmentRequirements(String factoryId) {
        return equipmentRequirements.findByFactoryId(factoryId);
    }

    /**
     * 把一个产品所需设备整体换成给定集合: 不在新集合里的在用行软删, 新增的插入, 不变的保留。
     * 空集合 = 清空 (插单将判设备「未配置」)。
     */
    @Transactional
    public List<ProductEquipmentRequirement> replaceEquipment(String factoryId, String productTypeId,
                                                              Collection<Long> equipmentIds, Source source,
                                                              Long configuredBy) {
        if (configuredBy == null) {
            throw new BusinessException(401, "无法识别当前操作人, 未保存");
        }
        if (productTypeId == null || productTypes.findByIdAndFactoryId(productTypeId, factoryId).isEmpty()) {
            throw new BusinessException(404, "产品不存在或不属于本厂: " + productTypeId);
        }
        Set<Long> wanted = new LinkedHashSet<>(equipmentIds == null ? List.of() : equipmentIds);
        wanted.remove(null);
        if (!wanted.isEmpty()) {
            Map<Long, FactoryEquipment> found = equipment.findByIdIn(wanted).stream()
                    .filter(e -> factoryId.equals(e.getFactoryId()))
                    .collect(Collectors.toMap(FactoryEquipment::getId, Function.identity()));
            List<Long> missing = wanted.stream().filter(id -> !found.containsKey(id)).toList();
            if (!missing.isEmpty()) {
                throw new BusinessException(404, "设备不存在或不属于本厂: " + missing);
            }
        }

        LocalDateTime now = LocalDateTime.now();
        List<ProductEquipmentRequirement> kept = new ArrayList<>();
        for (ProductEquipmentRequirement r : equipmentRequirements.findByFactoryIdAndProductTypeId(factoryId, productTypeId)) {
            if (wanted.remove(r.getEquipmentId())) {
                kept.add(r);
            } else {
                r.setDeletedAt(now);
                equipmentRequirements.saveAndFlush(r);
            }
        }
        for (Long id : wanted) {
            ProductEquipmentRequirement r = new ProductEquipmentRequirement();
            r.setFactoryId(factoryId);
            r.setProductTypeId(productTypeId);
            r.setEquipmentId(id);
            r.setSource(source);
            r.setConfiguredBy(configuredBy);
            kept.add(equipmentRequirements.saveAndFlush(r));
        }
        return kept;
    }
}
