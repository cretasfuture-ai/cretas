package com.cretas.aims.blueprint.canonical;

import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.CapabilityView;
import com.cretas.aims.blueprint.canonical.FactoryReadinessReport.BusinessDomain;
import com.cretas.aims.blueprint.canonical.FactoryReadinessReport.Check;
import com.cretas.aims.blueprint.canonical.FactoryReadinessReport.FirstRecord;
import com.cretas.aims.blueprint.canonical.FactoryReadinessReport.ProductRuntime;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.SupplierMaterial;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.SupplierMaterialPurchaseSpecRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.material.MaterialPackagingSpecRepository;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierMaterialRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
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
    /** Day-to-day post holders, i.e. someone besides the factory super admin who can do the work. */
    static final List<String> PURCHASE_ROLES = List.of("procurement_manager", "warehouse_manager");
    static final List<String> PRODUCTION_ROLES = List.of("workshop_supervisor", "operator");
    static final List<String> SHIPPING_ROLES = List.of("sales_manager", "warehouse_manager");

    private final WarehouseResolver warehouses;
    private final FactoryWarehouseRepository factoryWarehouses;
    private final SupplierRepository suppliers;
    private final CustomerRepository customers;
    private final RawMaterialTypeRepository rawMaterials;
    private final PurchaseOrderRepository purchaseOrders;
    private final ProductionPlanRepository productionPlans;
    private final SalesDeliveryRecordRepository deliveries;
    private final SupplierMaterialRepository supplierMaterials;
    private final UserRepository users;
    private final MaterialPackagingSpecRepository materialPackagingSpecs;
    private final SupplierMaterialPurchaseSpecRepository supplierPurchaseSpecs;
    private final UnitContractService unitContracts;

    public List<BusinessDomain> read(String factoryId, List<CapabilityView> capabilities,
            List<ProductRuntime> runtime) {
        return List.of(purchase(factoryId, capabilities), production(factoryId, capabilities, runtime),
                shipping(factoryId, capabilities, runtime));
    }

    private BusinessDomain purchase(String factoryId, List<CapabilityView> capabilities) {
        List<Check> checks = List.of(
                atLeastOne("ACTIVE_SUPPLIER_MATERIAL", "至少一个可下单的供应商-物料关系",
                        () -> activeSupplyRelationships(factoryId),
                        "SUPPLIERS", "供应商管理（供应原料）"),
                warehouse("PURCHASE_INBOUND_WAREHOUSE", "采购入库仓可用", factoryId,
                        warehouses::resolvePurchaseInboundWh),
                purchasePackaging(factoryId),
                postHolder("PURCHASE_POST_HOLDER", "有采购岗位账号可下单收货", factoryId, PURCHASE_ROLES));
        BusinessDomain purchaseDomain = domain("PURCHASE", "采购", PURCHASE_CAPABILITIES, capabilities, checks,
                List.of("采购价格（供应商规格价、含税参考价）在下单时校验，此处不检查",
                        "按基本单位采购无需包装换算，只核对按包装采购的供应关系",
                        "岗位账号只核对是否存在有效的采购/仓库岗位，不核对每个账号的具体菜单权限"),
                firstRecord(() -> purchaseOrders.findByFactoryIdOrderByCreatedAtDesc(
                                factoryId, PageRequest.of(0, 1)).getTotalElements(),
                        () -> purchaseOrders.findByFactoryIdAndStatusOrderByCreatedAtDesc(
                                factoryId, PurchaseOrderStatus.COMPLETED, PageRequest.of(0, 1)).getTotalElements(),
                        "采购单", "全部到货"));
        return "READY".equals(purchaseDomain.state()) ? purchaseDomain.withFirstAction(purchaseAction(factoryId))
                : purchaseDomain;
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
                        warehouses::resolveProductionRawWh),
                postHolder("PRODUCTION_POST_HOLDER", "有车间主管或操作员账号可报工", factoryId, PRODUCTION_ROLES));
        BusinessDomain productionDomain = domain("PRODUCTION", "生产", PRODUCTION_CAPABILITIES, capabilities, checks,
                List.of("建厂能力目录不含研发业务，不要求研发仓",
                        "岗位账号只核对是否存在有效的车间岗位，不核对班组编排和每个账号的具体菜单权限"),
                firstRecord(() -> productionPlans.countByFactoryId(factoryId),
                        () -> productionPlans.countByFactoryIdAndStatus(factoryId, ProductionPlanStatus.COMPLETED),
                        "生产计划", "已完工"));
        return "READY".equals(productionDomain.state())
                ? productionDomain.withFirstAction(productAction(runtime, "新建第一张生产计划", "/production/plans"))
                : productionDomain;
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
                        warehouses::resolveSalesOutboundWh),
                postHolder("SHIPPING_POST_HOLDER", "有销售或仓管岗位账号可发货", factoryId, SHIPPING_ROLES));
        BusinessDomain shippingDomain = domain("SHIPPING", "发货", SHIPPING_CAPABILITIES, capabilities, checks,
                List.of("按箱销售的箱规在下单时明确选择，此处不要求每个成品都有箱规",
                        "岗位账号只核对是否存在有效的销售/仓管岗位，不核对每个账号的具体菜单权限"),
                firstRecord(() -> deliveries.findByFactoryIdOrderByCreatedAtDesc(
                                factoryId, PageRequest.of(0, 1)).getTotalElements(),
                        () -> deliveries.findByFactoryIdAndStatusIn(factoryId, List.of(SalesDeliveryStatus.DELIVERED),
                                PageRequest.of(0, 1)).getTotalElements(),
                        "发货单", "客户已签收"));
        return "READY".equals(shippingDomain.state())
                ? shippingDomain.withFirstAction(salesAction(factoryId, runtime)) : shippingDomain;
    }

    private FactoryReadinessReport.FirstAction purchaseAction(String factoryId) {
        try {
            return activeSupplyRelations(factoryId).stream().findFirst()
                    .map(relation -> new FactoryReadinessReport.FirstAction("新建第一张采购单", "/procurement/orders",
                            java.util.Map.of("fromReadiness", "1", "supplierId", relation.getSupplierId(),
                                    "materialTypeId", relation.getMaterialTypeId()),
                            "带入已建立供应关系的供应商和原料"))
                    .orElse(null);
        } catch (RuntimeException failure) {
            log.warn("factory readiness purchase action unreadable: {}", failure.getMessage());
            return null;
        }
    }

    private FactoryReadinessReport.FirstAction salesAction(String factoryId, List<ProductRuntime> runtime) {
        try {
            var customer = customers.findByFactoryIdAndIsActive(factoryId, true).stream().findFirst();
            var product = runtime.stream().filter(row -> "PUBLISHED".equals(row.state())).findFirst();
            if (customer.isEmpty() || product.isEmpty()) return null;
            return new FactoryReadinessReport.FirstAction("新建第一张销售订单", "/sales/orders/new",
                    java.util.Map.of("fromReadiness", "1", "customerId", String.valueOf(customer.get().getId()),
                            "productId", product.get().productId()),
                    "带入启用的客户和已生效的成品");
        } catch (RuntimeException failure) {
            log.warn("factory readiness sales action unreadable: {}", failure.getMessage());
            return null;
        }
    }

    private static FactoryReadinessReport.FirstAction productAction(List<ProductRuntime> runtime, String label,
            String path) {
        return runtime.stream().filter(row -> "PUBLISHED".equals(row.state())).findFirst()
                .map(product -> new FactoryReadinessReport.FirstAction(label, path,
                        java.util.Map.of("fromReadiness", "1", "productTypeId", product.productId()),
                        "带入已生效工艺与BOM的成品「" + product.productName() + "」"))
                .orElse(null);
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

    /**
     * Only relationships bought by package need a conversion: each must carry an active purchase spec,
     * or exactly one active material packaging spec of that package unit (the rule the order path
     * applies). Buying in the base unit needs none and never counts as missing.
     */
    private Check purchasePackaging(String factoryId) {
        String code = "PACKAGING_PURCHASE_CONVERSION";
        String label = "按包装采购的供应关系都有包装换算";
        String remedyKey = "MATERIAL_PACKAGING";
        String remedyLabel = "原料类型（包装规格）";
        try {
            java.util.Map<String, RawMaterialType> materialsById = new java.util.HashMap<>();
            for (RawMaterialType material : rawMaterials.findByFactoryIdAndIsActive(factoryId, true)) {
                materialsById.put(material.getId(), material);
            }
            int packaged = 0;
            List<String> gaps = new java.util.ArrayList<>();
            for (var supplier : suppliers.findByFactoryIdAndIsActive(factoryId, true)) {
                for (SupplierMaterial relation : supplierMaterials
                        .findByFactoryIdAndSupplierIdOrderByActiveDescPreferredDescCreatedAtDesc(
                                factoryId, supplier.getId())) {
                    RawMaterialType material = materialsById.get(relation.getMaterialTypeId());
                    if (!Boolean.TRUE.equals(relation.getActive()) || material == null) continue;
                    String purchaseUnit = relation.getPurchaseUnit();
                    if (purchaseUnit == null || purchaseUnit.isBlank()
                            || sameUnit(factoryId, purchaseUnit, material.getUnit())) continue;
                    packaged++;
                    boolean hasPurchaseSpec = !supplierPurchaseSpecs
                            .findByFactoryIdAndSupplierMaterialIdAndActiveTrue(factoryId, relation.getId()).isEmpty();
                    long matching = materialPackagingSpecs
                            .findByFactoryIdAndMaterialTypeIdAndActiveTrueOrderBySortOrderAscCreatedAtAsc(
                                    factoryId, material.getId()).stream()
                            .filter(spec -> sameUnit(factoryId, spec.getPackageUnit(), purchaseUnit))
                            .count();
                    if (!hasPurchaseSpec && matching != 1) {
                        gaps.add(supplier.getName() + "·" + material.getName() + "（" + purchaseUnit
                                + (matching == 0 ? "，缺换算" : "，换算不唯一") + "）");
                    }
                }
            }
            if (packaged == 0) {
                return new Check(code, label, "MET", "没有按包装采购的供应关系，无需换算", remedyKey, remedyLabel);
            }
            return gaps.isEmpty()
                    ? new Check(code, label, "MET", packaged + " 个按包装采购的供应关系均有换算", remedyKey, remedyLabel)
                    : new Check(code, label, "MISSING", "缺换算：" + String.join("；", gaps), remedyKey, remedyLabel);
        } catch (RuntimeException failure) {
            log.warn("factory readiness check {} unreadable: {}", code, failure.getMessage());
            return new Check(code, label, "UNKNOWN", "读取失败，不能判断", remedyKey, remedyLabel);
        }
    }

    private boolean sameUnit(String factoryId, String a, String b) {
        if (a == null || b == null) return false;
        UnitNormalizationResult left = unitContracts.normalize(factoryId, a);
        UnitNormalizationResult right = unitContracts.normalize(factoryId, b);
        String leftCode = left != null && left.recognized() ? left.code() : a.trim();
        String rightCode = right != null && right.recognized() ? right.code() : b.trim();
        return leftCode.equals(rightCode);
    }

    /** At least one active account of this factory holds one of the day-to-day roles. */
    private Check postHolder(String code, String label, String factoryId, List<String> roles) {
        String remedyKey = "USERS";
        String remedyLabel = "用户与角色管理";
        try {
            long holders = roles.stream()
                    .flatMap(role -> users.findByFactoryIdAndRoleCode(factoryId, role).stream())
                    .filter(user -> Boolean.TRUE.equals(user.getIsActive()))
                    .map(User::getId).distinct().count();
            return new Check(code, label, holders > 0 ? "MET" : "MISSING",
                    holders > 0 ? "当前 " + holders + " 个有效岗位账号"
                            : "没有人担任 " + String.join(" / ", roles) + " 岗位",
                    remedyKey, remedyLabel);
        } catch (RuntimeException failure) {
            log.warn("factory readiness check {} unreadable: {}", code, failure.getMessage());
            return new Check(code, label, "UNKNOWN", "读取失败，不能判断", remedyKey, remedyLabel);
        }
    }

    /**
     * A document existing (any status, drafts included) is not a completed first business: only
     * the domain's own terminal status counts (purchase COMPLETED = fully received, production
     * plan COMPLETED, delivery DELIVERED = signed by the customer).
     */
    private static FirstRecord firstRecord(Supplier<Long> count, Supplier<Long> completed, String noun,
            String terminal) {
        try {
            long total = count.get();
            long done = completed.get();
            if (done > 0) {
                return new FirstRecord("COMPLETED", total, done,
                        done + " 张" + noun + terminal + "（共 " + total + " 张）");
            }
            return total > 0
                    ? new FirstRecord("RECORDED", total, 0L,
                            "已有 " + total + " 张" + noun + "，没有一张" + terminal + "，首笔业务未完成")
                    : new FirstRecord("NONE", 0L, 0L, "尚无" + noun);
        } catch (RuntimeException failure) {
            log.warn("factory readiness first {} unreadable: {}", noun, failure.getMessage());
            return new FirstRecord("UNKNOWN", null, null, "读取失败，不能判断");
        }
    }

    /**
     * Same-factory supply relationships the purchase-order guard would accept: an active
     * relationship whose supplier is active and whose material is an active material of this
     * factory (soft-deleted rows are filtered by the entities' @Where).
     */
    private long activeSupplyRelationships(String factoryId) {
        return activeSupplyRelations(factoryId).size();
    }

    private List<SupplierMaterial> activeSupplyRelations(String factoryId) {
        Set<String> activeMaterials = rawMaterials.findByFactoryIdAndIsActive(factoryId, true).stream()
                .map(RawMaterialType::getId).collect(Collectors.toSet());
        if (activeMaterials.isEmpty()) return List.of();
        return suppliers.findByFactoryIdAndIsActive(factoryId, true).stream()
                .flatMap(supplier -> supplierMaterials
                        .findByFactoryIdAndSupplierIdOrderByActiveDescPreferredDescCreatedAtDesc(factoryId, supplier.getId())
                        .stream())
                .filter(relation -> Boolean.TRUE.equals(relation.getActive())
                        && factoryId.equals(relation.getFactoryId())
                        && activeMaterials.contains(relation.getMaterialTypeId()))
                .toList();
    }
}
