package com.cretas.aims.service.entity;

import java.util.Map;
import java.util.Optional;

/** {@link FactoryEntityResolver} 认的本厂主数据实体种类。 */
public enum EntityKind {
    CUSTOMER("客户", true),
    SUPPLIER("供应商", true),
    PRODUCT("产品", false),
    MATERIAL("物料", false),
    EMPLOYEE("员工", false);

    /** 回复措辞用的中文名。 */
    public final String label;
    /** 是否按单位名称去掉「有限公司 / 店 …」后缀再比 (只有交易对手的单位名)。 */
    final boolean organization;

    EntityKind(String label, boolean organization) {
        this.label = label;
        this.organization = organization;
    }

    /**
     * 工具参数名 → 实体种类。只登记「这个参数的值就是一个本厂主数据的名字」的参数名; 不在表里的参数
     * 不做实体解析。要扩就扩这里, 只有这一处。
     */
    static final Map<String, EntityKind> NAME_PARAMETERS = Map.of(
            "customerName", CUSTOMER,
            "supplierName", SUPPLIER,
            "productName", PRODUCT,
            "materialName", MATERIAL,
            "rawMaterialName", MATERIAL,
            "materialTypeName", MATERIAL,
            "employeeName", EMPLOYEE,
            // user_create 的姓名参数 (新建账号 = 新建员工); 只有 UserCreateTool 用这个参数名
            "fullName", EMPLOYEE);

    public static Optional<EntityKind> forParameter(String parameterName) {
        return Optional.ofNullable(parameterName == null ? null : NAME_PARAMETERS.get(parameterName));
    }

    /**
     * 新建这一种实体的工具 (只登记真实存在的工具: customer_create / supplier_create / product_create / user_create)。
     * 这类工具的名字参数就是一个本来不在主数据里的新名字: 填参数时 ⛔ 不因「主数据里没有」丢掉, 也不换成已有对象的名字。
     * 只认「建实体本身」的工具 —— purchase_order_create 之类引用已有供应商 / 物料的工具不在此列。
     */
    static final Map<String, EntityKind> CREATING_TOOLS = Map.of(
            "customer_create", CUSTOMER,
            "supplier_create", SUPPLIER,
            "product_create", PRODUCT,
            "user_create", EMPLOYEE);

    public static Optional<EntityKind> createdBy(String toolName) {
        return Optional.ofNullable(toolName == null ? null : CREATING_TOOLS.get(toolName));
    }
}
