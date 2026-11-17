package com.cretas.aims.service.crew;

/**
 * 只在测试源码里: 设置 {@link DigitalCrewService} 的包级测试接缝 (#1771 第 3 轮 N1)。
 * 生产代码看不到这个类, 也就没有任何生产调用方能改这个接缝。
 */
public final class DigitalCrewServiceTestSeams {

    private DigitalCrewServiceTestSeams() {
    }

    /** 在「数完本人已交办件数」之后、插入之前运行; 传 null 恢复为什么都不做。 */
    public static void setAfterOpenCountHook(DigitalCrewService service, Runnable hook) {
        service.afterOpenCountHook = hook == null ? () -> { } : hook;
    }
}
