package com.cretas.aims.blueprint.interview;

import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitResult;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.factory.FactoryWarehouse.WarehouseType;
import com.cretas.aims.entity.factory.FactoryWarehouseDefault;
import com.cretas.aims.entity.factory.WarehouseDefaultPurpose;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.factory.FactoryWarehouseDefaultRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 蓝图落库时给它登记的仓库配上默认用途 —— 否则采购入库 / 生产领料 / 生产仓 / 销售出货
 * 全部回退到 WH-LOG / WH-WKS, 而蓝图建的厂没有这两个系统仓, 业务路径当场 500
 * (2026-10-21 DEMO_2026_001: 蓝图 APPLIED, 四条路由全 MISSING)。
 *
 * <h2>映射只写有依据的</h2>
 *
 * 依据 = 业务路径实际怎么用这个用途 × 仓库类型的定义 ({@link FactoryWarehouse.WarehouseType}):
 * <ul>
 *   <li>RAW → 采购入库 / 生产领料: 原料仓就是「存放采购入库的原辅料」; 报工/结单闸本来就认 RAW 仓的批次。</li>
 *   <li>WORKSHOP → 生产仓: 领料单目标仓按 WORKSHOP 类型取, 报工/结单闸认 WORKSHOP 仓的批次。
 *       ⛔ WIP / LINESIDE 不映射 —— 那两道闸不认它们的批次, 领进去的料报工时会被 409 拒。</li>
 *   <li>FINISHED → 销售出货: 成品仓就是「存放完工待出库的成品」。</li>
 * </ul>
 * ⛔ 不写: LOGISTICS_DEFAULT(调用方既有原料入库/调拨, 也有成品库存匹配, 一种仓型撑不起两种语义);
 * RD_DEFAULT(建厂能力目录没有研发业务)。
 *
 * <h2>只补空缺</h2>
 *
 * 已存过的配置一律不动(管理员存的, 或上次落库补的; 哪怕它指向的仓已删 —— 那是管理员的决定, 由就绪报告提示);
 * 用途已经能路由到可用仓(系统仓回退)也不动 —— 已经能跑的厂不因重跑建厂改路由。
 * 同一类型蓝图里有两个以上的仓, 不替用户挑, 留空并说明。所以重复落库幂等。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true")
public class BlueprintWarehouseDefaultsWriter {

    /** 用途 → 唯一有依据的仓库类型。顺序即回执顺序。 */
    static final Map<WarehouseDefaultPurpose, WarehouseType> PURPOSE_TYPES;

    static {
        Map<WarehouseDefaultPurpose, WarehouseType> types = new LinkedHashMap<>();
        types.put(WarehouseDefaultPurpose.PURCHASE_INBOUND_DEFAULT, WarehouseType.RAW);
        types.put(WarehouseDefaultPurpose.PRODUCTION_RAW_DEFAULT, WarehouseType.RAW);
        types.put(WarehouseDefaultPurpose.WORKSHOP_DEFAULT, WarehouseType.WORKSHOP);
        types.put(WarehouseDefaultPurpose.SALES_OUTBOUND_DEFAULT, WarehouseType.FINISHED);
        PURPOSE_TYPES = Collections.unmodifiableMap(types);
    }

    private final FactoryWarehouseRepository warehouses;
    private final FactoryWarehouseDefaultRepository defaults;
    private final WarehouseResolver resolver;

    /**
     * @param blueprintCodes 蓝图登记的仓库编号(本次新建的和库里已有被跳过的都算 —— 蓝图都点了名)
     */
    public void write(String factoryId, Collection<String> blueprintCodes, ApplyAIInitResult result) {
        List<FactoryWarehouse> named = new LinkedHashSet<>(blueprintCodes).stream()
                .filter(Objects::nonNull)
                .map(code -> warehouses.findByFactoryIdAndCodeAndDeletedAtIsNull(factoryId, code.trim()))
                .flatMap(Optional::stream)
                .toList();
        for (Map.Entry<WarehouseDefaultPurpose, WarehouseType> entry : PURPOSE_TYPES.entrySet()) {
            WarehouseDefaultPurpose purpose = entry.getKey();
            WarehouseType type = entry.getValue();
            if (defaults.findByFactoryIdAndPurposeAndDeletedAtIsNull(factoryId, purpose).isPresent()) {
                result.getSkippedWarehouseDefaults().add(purpose + "(已有配置)");
                continue;
            }
            FactoryWarehouse routed = liveRoute(factoryId, purpose);
            if (routed != null) {
                result.getSkippedWarehouseDefaults().add(purpose + "(已可用: " + routed.getCode() + ")");
                continue;
            }
            List<FactoryWarehouse> candidates = named.stream()
                    .filter(w -> w.getType() == type && !Boolean.FALSE.equals(w.getIsActive()))
                    .toList();
            if (candidates.size() != 1) {
                result.getSkippedWarehouseDefaults().add(purpose + (candidates.isEmpty()
                        ? "(蓝图没有启用的 " + type + " 仓)"
                        : "(蓝图有 " + candidates.size() + " 个 " + type + " 仓, 不替用户挑)"));
                continue;
            }
            FactoryWarehouse target = candidates.get(0);
            FactoryWarehouseDefault row = new FactoryWarehouseDefault();
            row.setFactoryId(factoryId);
            row.setPurpose(purpose);
            row.setWarehouseId(target.getId());
            defaults.saveAndFlush(row);
            // 读回: 业务路径用的就是这个 resolver; 读不回来就是写错了地方, 让整个落库回滚。
            FactoryWarehouse readBack = liveRoute(factoryId, purpose);
            if (readBack == null || !target.getId().equals(readBack.getId())) {
                throw new IllegalStateException("默认仓写入后读不回来: factoryId=" + factoryId
                        + " purpose=" + purpose + " 期望 " + target.getCode());
            }
            result.getWrittenWarehouseDefaults().add(purpose + "→" + target.getCode());
            log.info("建厂默认仓 factoryId={} purpose={} warehouse={}", factoryId, purpose, target.getCode());
        }
    }

    /** 业务路径此刻会把这个用途路由到哪个仓; 路由不到或仓已停用 → null。 */
    private FactoryWarehouse liveRoute(String factoryId, WarehouseDefaultPurpose purpose) {
        String id;
        try {
            id = switch (purpose) {
                case PURCHASE_INBOUND_DEFAULT -> resolver.resolvePurchaseInboundWh(factoryId);
                case PRODUCTION_RAW_DEFAULT -> resolver.resolveProductionRawWh(factoryId);
                case WORKSHOP_DEFAULT -> resolver.resolveWorkshopId(factoryId);
                case SALES_OUTBOUND_DEFAULT -> resolver.resolveSalesOutboundWh(factoryId);
                default -> throw new IllegalArgumentException("未映射的用途 " + purpose);
            };
        } catch (BusinessException missingSeed) {
            return null;
        }
        return warehouses.findByIdAndFactoryIdAndDeletedAtIsNull(id, factoryId)
                .filter(w -> !Boolean.FALSE.equals(w.getIsActive()))
                .orElse(null);
    }
}
