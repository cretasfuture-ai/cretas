package com.cretas.aims.service.processsecret;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

/**
 * 把读数打标接到判定服务的缓存名单上: 没有保密产品的工厂, 加载工艺图时不解析图 JSON
 * (打标是 Hibernate PostLoad 里的静态钩子, 拿不到 Spring bean, 所以在这里接线)。
 */
@Component
public class ProcessSecretReadTrackerProbe {

    private final ProcessSecretAccessService accessService;

    public ProcessSecretReadTrackerProbe(ProcessSecretAccessService accessService) {
        this.accessService = accessService;
    }

    @PostConstruct
    void install() {
        ProcessSecretReadTracker.useProtectedFactoryProbe(
                factoryId -> !accessService.protectedProductTypeIds(factoryId).isEmpty());
    }

    @PreDestroy
    void uninstall() {
        ProcessSecretReadTracker.useProtectedFactoryProbe(null);
    }
}
