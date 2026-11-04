package com.cretas.aims.service.gate;

/** 诊断卡「去解开」跳转的 web-admin 路由 (与 web-admin router/index.ts 同步)。 */
public final class GateRoutes {
    public static final String QC_RELEASE = "/quality/release";
    public static final String SHORTAGE = "/production/shortage-cases";
    public static final String PURCHASE = "/procurement/purchase-orders";
    public static final String STOCK = "/warehouse/inventory";
    private GateRoutes() {}
}
