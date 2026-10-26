package com.cretas.aims.service.processsecret;

/** 测试用: 在桩里模拟「真实服务读到了这条实体」, 与生产上 Hibernate PostLoad 走同一个入口。 */
public final class ProcessSecretTestHooks {

    private ProcessSecretTestHooks() {
    }

    public static void record(Object loadedEntity) {
        ProcessSecretReadTracker.record(loadedEntity);
    }
}
