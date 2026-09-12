package com.cretas.aims.audit.inventory;

import com.cretas.aims.entity.MaterialBatch;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the actual primary factory, including the application's manually configured persistence unit. */
@Configuration(proxyBeanMethods = false)
public class InventoryAuditConfiguration {
    @Bean
    static BeanPostProcessor inventoryAuditFactoryPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof EntityManagerFactory factory)
                        || factory.getMetamodel().getEntities().stream().noneMatch(entity -> entity.getJavaType() == MaterialBatch.class)) {
                    return bean;
                }
                var sessionFactory = factory.unwrap(SessionFactoryImplementor.class);
                var registry = sessionFactory.getServiceRegistry().getService(EventListenerRegistry.class);
                for (var registered : registry.getEventListenerGroup(EventType.POST_INSERT).listeners()) {
                    if (registered instanceof InventoryAuditListener) return bean;
                }
                var listener = new InventoryAuditListener();
                registry.appendListeners(EventType.POST_INSERT, listener);
                registry.appendListeners(EventType.POST_UPDATE, listener);
                registry.appendListeners(EventType.POST_DELETE, listener);
                LoggerFactory.getLogger(InventoryAuditConfiguration.class)
                        .info("inventory_audit_ready persistenceBean={} entities=6 transaction=same_connection", beanName);
                return bean;
            }
        };
    }
}
