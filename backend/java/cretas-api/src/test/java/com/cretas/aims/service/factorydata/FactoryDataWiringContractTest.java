package com.cretas.aims.service.factorydata;

import com.cretas.aims.controller.platform.PlatformFactoryDataController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring 只能从「唯一的构造器」或「唯一一个标了 @Autowired 的构造器」创建 bean。
 *
 * <p>2026-10-26: 这几个类都另有一个包内构造器给测试注入时钟 / schema, 生产构造器没标 @Autowired,
 * Spring 退回去找无参构造器 ⇒ Fresh DB 闸里应用启动即失败。PG 契约测试是手工 new 的, 测不到这一层。
 */
class FactoryDataWiringContractTest {

    static final List<Class<?>> BEANS = List.of(
            FactoryDataService.class, FactoryErasureService.class, FactoryDataRequestLedger.class,
            FactoryErasureRetentionJob.class, OssFactoryObjectStore.class, PlatformFactoryDataController.class);

    @Test
    void everyBeanHasOneConstructorSpringCanPick() {
        for (Class<?> c : BEANS) {
            Constructor<?>[] all = c.getDeclaredConstructors();
            long autowired = Arrays.stream(all).filter(k -> k.isAnnotationPresent(Autowired.class)).count();
            assertThat(all.length == 1 || autowired == 1)
                    .as(c.getSimpleName() + ": " + all.length + " 个构造器, " + autowired + " 个标了 @Autowired")
                    .isTrue();
        }
    }
}
