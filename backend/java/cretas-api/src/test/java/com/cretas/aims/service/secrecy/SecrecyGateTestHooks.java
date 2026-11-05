package com.cretas.aims.service.secrecy;

/** 别的包里的源码闸复用 {@link FactorySecrecyGatesContractTest} 的去注释逻辑 (一份, 不抄)。 */
public final class SecrecyGateTestHooks {

    private SecrecyGateTestHooks() {
    }

    public static String stripJavaComments(String code) {
        return FactorySecrecyGatesContractTest.stripJavaComments(code);
    }
}
