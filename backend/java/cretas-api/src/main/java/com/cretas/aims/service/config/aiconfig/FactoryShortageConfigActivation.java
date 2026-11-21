package com.cretas.aims.service.config.aiconfig;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Shared opt-in for the isolated shortage configuration contract. */
public final class FactoryShortageConfigActivation implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return isEnabled(context.getEnvironment());
    }

    public static boolean isEnabled(Environment environment) {
        if (environment == null) {
            return false;
        }
        String[] activeProfiles = environment.getActiveProfiles();
        return activeProfiles.length == 1
                && "test".equals(activeProfiles[0])
                && "true".equalsIgnoreCase(environment.getProperty(FactoryShortageConfigService.ENABLED_PROPERTY));
    }
}
