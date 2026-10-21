package com.cretas.aims.blueprint.canonical;

import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.CapabilityView;
import com.cretas.aims.blueprint.canonical.FactoryReadinessReport.BusinessDomain;
import com.cretas.aims.blueprint.canonical.FactoryReadinessReport.Check;
import com.cretas.aims.blueprint.canonical.FactoryReadinessReport.FirstRecord;
import com.cretas.aims.blueprint.canonical.FactoryReadinessReport.ProductRuntime;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.ShipmentRecordRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * Reads whether a factory can actually purchase, produce and ship, from the same facts the
 * business paths use (warehouse routing through {@link WarehouseResolver}, active master data,
 * existing documents). Read-only: no repository here is written to.
 *
 * <p>Applicability follows the capabilities the blueprint included. Anything not checked is
 * listed in {@code notChecked} instead of being silently treated as satisfied; any read failure
 * is UNKNOWN, which never rolls up to READY.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true")
public class FactoryBusinessReadinessReader {
    static final Set<String> PURCHASE_CAPABILITIES = Set.of("materials", "supplier-receiving");
    static final Set<String> PRODUCTION_CAPABILITIES = Set.of("processes", "routes", "bom", "workflow");
    static final Set<String> SHIPPING_CAPABILITIES = Set.of("products");

    private final WarehouseResolver warehouses;
    private final FactoryWarehouseRepository factoryWarehouses;
    private final SupplierRepository suppliers;
    private final CustomerRepository customers;
    private final RawMaterialTypeRepository rawMaterials;
    private final PurchaseOrderRepository purchaseOrders;
    private final ProductionPlanRepository productionPlans;
    private final ShipmentRecordRepository shipments;

    public List<BusinessDomain> read(String factoryId, List<CapabilityView> capabilities,
            List<ProductRuntime> runtime) {
        return List.of(purchase(factoryId, capabilities), production(factoryId, capabilities, runtime),
                shipping(factoryId, capabilities, runtime));
    }

    private BusinessDomain purchase(String factoryId, List<CapabilityView> capabilities) {
        List<Check> checks = List.of(
                atLeastOne("ACTIVE_SUPPLIER", "至少一个启用的供应商",
                        () -> suppliers.countByFactoryIdAndIsActive(factoryId, true),
                        "SUPPLIERS", "供应商管理"),
                atLeastOne("ACTIVE_RAW_MATERIAL", "至少一种启用的原料/包材类型",
                        () -> rawMaterials.countByFactoryIdAndIsActive(factoryId, true),
                        "MATERIAL_TYPES", "原料类型字典"),
                warehouse("PURCHASE_INBOUND_WAREHOUSE", "采购入库仓可用", factoryId,
                        warehouses::resolvePurchaseInboundWh));
        return domain("PURCHASE", "采购", PURCHASE_CAPABILITIES, capabilities, checks,
                List.of("按包装采购的换算按采购明细与供应商规格核对；按基本单位采购无需包装换算，此处不要求",
                        "采购岗位账号与权限另行交付，此处不检查"),
                firstRecord(() -> purchaseOrders.findByFactoryIdOrderByCreatedAtDesc(
                        factoryId, PageRequest.of(0, 1)).getTotalElements(), "采购单"));
    }

    private BusinessDomain production(String factoryId, List<CapabilityView> capabilities,
            List<ProductRuntime> runtime) {
        long published = runtime.stream().filter(row -> "PUBLISHED".equals(row.state())).count();
        Check runtimeCheck = runtime.isEmpty()
                ? new Check("PUBLISHED_PRODUCT_RUNTIME", "至少一个成品的工艺与BOM已正式生效", "MISSING",
                        "尚无在用成品", "PRODUCTS", "SKU 管理")
                : new Check("PUBLISHED_PRODUCT_RUNTIME", "至少一个成品的工艺与BOM已正式生效",
                        published > 0 ? "MET" : "MISSING",
                        published + "/" + runtime.size() + " 个在用成品已生效并匹配",
                        "PRODUCT_PROCESSES", "产品工序链");
        List<Check> checks = List.of(runtimeCheck,
                warehouse("WORKSHOP_WAREHOUSE", "生产成品/报工仓可用", factoryId, warehouses::resolveWorkshopId),
                warehouse("PRODUCTION_RAW_WAREHOUSE", "生产领料仓可用", factoryId,
                        warehouses::resolveProductionRawWh));
        return domain("PRODUCTION", "生产", PRODUCTION_CAPABILITIES, capabilities, checks,
                List.of("建厂能力目录不含研发业务，不要求研发仓",
                        "操作员与班组账号、岗位权限另行交付，此处不检查"),
                firstRecord(() -> productionPlans.countByFactoryId(factoryId), "生产计划"));
    }

    private BusinessDomain shipping(String factoryId, List<CapabilityView> capabilities,
            List<ProductRuntime> runtime) {
        List<Check> checks = List.of(
                atLeastOne("ACTIVE_CUSTOMER", "至少一个启用的客户",
                        () -> customers.countByFactoryIdAndIsActive(factoryId, true),
                        "CUSTOMERS", "客户管理"),
                new Check("SALEABLE_PRODUCT", "至少一个在用成品", runtime.isEmpty() ? "MISSING" : "MET",
                        runtime.size() + " 个在用成品", "PRODUCTS", "SKU 管理"),
                warehouse("SALES_OUTBOUND_WAREHOUSE", "销售出货仓可用", factoryId,
                        warehouses::resolveSalesOutboundWh));
        return domain("SHIPPING", "发货", SHIPPING_CAPABILITIES, capabilities, checks,
                List.of("按箱销售的箱规在下单时明确选择，此处不要求每个成品都有箱规",
                        "销售与仓管岗位账号、权限另行交付，此处不检查"),
                firstRecord(() -> shipments.countByFactoryId(factoryId), "出货记录"));
    }

    static BusinessDomain domain(String domain, String label, Set<String> capabilityIds,
            List<CapabilityView> capabilities, List<Check> checks, List<String> notChecked,
            FirstRecord firstRecord) {
        List<String> included = capabilities == null ? List.of() : capabilities.stream()
                .filter(CapabilityView::isIncluded).map(CapabilityView::getId)
                .filter(capabilityIds::contains).sorted().toList();
        if (capabilities == null || capabilities.isEmpty()) {
            return new BusinessDomain(domain, label, "UNKNOWN", "蓝图未提供能力清单，无法判断是否适用",
                    "UNKNOWN", checks, notChecked, firstRecord);
        }
        if (included.isEmpty()) {
            return new BusinessDomain(domain, label, "NOT_APPLICABLE",
                    "本次蓝图未选择 " + String.join("/", capabilityIds.stream().sorted().toList()),
                    "NOT_APPLICABLE", checks, notChecked, firstRecord);
        }
        String state = checks.stream().anyMatch(check -> "MISSING".equals(check.state())) ? "MISSING"
                : checks.stream().anyMatch(check -> "UNKNOWN".equals(check.state())) ? "UNKNOWN" : "READY";
        return new BusinessDomain(domain, label, "APPLICABLE", "蓝图已选择 " + String.join("/", included),
                state, checks, notChecked, firstRecord);
    }

    private Check atLeastOne(String code, String label, LongSupplier count, String remedyKey,
            String remedyLabel) {
        try {
            long value = count.getAsLong();
            return new Check(code, label, value > 0 ? "MET" : "MISSING", "当前 " + value + " 个",
                    remedyKey, remedyLabel);
        } catch (RuntimeException failure) {
            log.warn("factory readiness check {} unreadable: {}", code, failure.getMessage());
            return new Check(code, label, "UNKNOWN", "读取失败，不能判断", remedyKey, remedyLabel);
        }
    }

    /** Uses the resolver the business path uses, then confirms the target is a live, active warehouse. */
    private Check warehouse(String code, String label, String factoryId, Function<String, String> resolve) {
        try {
            String id = resolve.apply(factoryId);
            var target = factoryWarehouses.findByIdAndFactoryIdAndDeletedAtIsNull(id, factoryId);
            if (target.isEmpty()) {
                return new Check(code, label, "MISSING", "路由到的仓库不存在或已删除",
                        "WAREHOUSE_DEFAULTS", "仓库默认用途");
            }
            FactoryWarehouse warehouse = target.get();
            boolean active = !Boolean.FALSE.equals(warehouse.getIsActive());
            return new Check(code, label, active ? "MET" : "MISSING",
                    (active ? "使用「" : "已停用：「") + warehouse.getName() + "」",
                    "WAREHOUSE_DEFAULTS", "仓库默认用途");
        } catch (BusinessException missing) {
            // No configured default and no seed warehouse: the business path would fail the same way.
            return new Check(code, label, "MISSING", "未配置默认仓，也没有可回退的系统仓",
                    "WAREHOUSE_DEFAULTS", "仓库默认用途");
        } catch (RuntimeException failure) {
            log.warn("factory readiness check {} unreadable: {}", code, failure.getMessage());
            return new Check(code, label, "UNKNOWN", "读取失败，不能判断", "WAREHOUSE_DEFAULTS", "仓库默认用途");
        }
    }

    private static FirstRecord firstRecord(Supplier<Long> count, String noun) {
        try {
            long value = count.get();
            return value > 0
                    ? new FirstRecord("RECORDED", value, "已有 " + value + " 张" + noun + "，完成情况以原单为准")
                    : new FirstRecord("NONE", 0L, "尚无" + noun);
        } catch (RuntimeException failure) {
            log.warn("factory readiness first {} unreadable: {}", noun, failure.getMessage());
            return new FirstRecord("UNKNOWN", null, "读取失败，不能判断");
        }
    }
}
