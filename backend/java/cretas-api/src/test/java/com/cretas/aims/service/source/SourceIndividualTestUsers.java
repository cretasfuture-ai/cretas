package com.cretas.aims.service.source;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryType;
import jakarta.persistence.EntityManager;

import java.util.UUID;

/**
 * 测试里落原料批次要一个真实存在的用户: {@code material_batches.created_by → users.id}、
 * {@code users.factory_id → factories.id} 在 H2(ddl-auto) 上都是真 FK。
 *
 * <p>⛔ 不要写死 {@code createdBy(1L)}: 那只在同一个 JVM 里恰好有别的测试类留下 id=1 的用户时才过,
 * 单跑这个类就红(2026-11-18 #1844 二轮单类实测)。
 */
final class SourceIndividualTestUsers {

    private SourceIndividualTestUsers() {
    }

    /** 在给定持久化上下文里建(或复用)工厂并新建一个用户, flush 后返回用户 id。调用方负责事务。 */
    static Long persistUser(EntityManager em, String factoryId) {
        if (em.find(Factory.class, factoryId) == null) {
            Factory factory = new Factory();
            factory.setId(factoryId);
            factory.setName("source individual test " + factoryId);
            factory.setType(FactoryType.FACTORY);
            factory.setLevel(0);
            factory.setIsActive(true);
            factory.setManuallyVerified(false);
            factory.setAiWeeklyQuota(20);
            em.persist(factory);
        }
        User user = new User();
        user.setFactoryId(factoryId);
        user.setUsername("si-test-" + UUID.randomUUID());
        user.setPasswordHash("x");
        user.setIsActive(true);
        em.persist(user);
        em.flush();
        return user.getId();
    }
}
