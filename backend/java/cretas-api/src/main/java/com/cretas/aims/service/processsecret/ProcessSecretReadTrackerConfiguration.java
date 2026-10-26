package com.cretas.aims.service.processsecret;

import com.cretas.aims.entity.bom.BomRecipe;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 把 {@link ProcessSecretReadTracker} 挂到持有配方实体的那个持久化单元上 (与 InventoryAuditConfiguration 同一方式)。 */
@Configuration(proxyBeanMethods = false)
public class ProcessSecretReadTrackerConfiguration {

    @Bean
    static BeanPostProcessor processSecretReadTrackerPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof EntityManagerFactory factory)
                        || factory.getMetamodel().getEntities().stream()
                                .noneMatch(entity -> entity.getJavaType() == BomRecipe.class)) {
                    return bean;
                }
                var registry = factory.unwrap(SessionFactoryImplementor.class).getServiceRegistry()
                        .getService(EventListenerRegistry.class);
                for (var registered : registry.getEventListenerGroup(EventType.POST_LOAD).listeners()) {
                    if (registered instanceof ProcessSecretReadTracker) return bean;
                }
                registry.appendListeners(EventType.POST_LOAD, new ProcessSecretReadTracker());
                LoggerFactory.getLogger(ProcessSecretReadTrackerConfiguration.class)
                        .info("process_secret_read_tracker_ready persistenceBean={}", beanName);
                return bean;
            }
        };
    }
}
