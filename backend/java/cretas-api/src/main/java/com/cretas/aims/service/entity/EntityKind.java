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
            "employeeName", EMPLOYEE);

    public static Optional<EntityKind> forParameter(String parameterName) {
        return Optional.ofNullable(parameterName == null ? null : NAME_PARAMETERS.get(parameterName));
    }
}
