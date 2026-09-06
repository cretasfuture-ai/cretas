package com.cretas.aims.repository;

import jakarta.persistence.Column;
import jakarta.persistence.EntityManager;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.SQLDelete;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.support.Repositories;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 带 {@code @Version} 的实体，类级 {@code @SQLDelete} 的 SQL 必须把 version 列也绑进去：
 * Hibernate 对 versioned 实体的自定义删除语句绑 <b>两个</b> 参数（id, version），SQL 只写 {@code WHERE id = ?}
 * 时第二个参数绑不上——H2 报 {@code Unable to bind parameter #2}，PostgreSQL JDBC 报 column index out of range——
 * 也就是说这些实体的 {@code repository.delete()} 在 prod 上从来就没成功过（2026-09-06 census：5 个实体全部如此，
 * 其中 4 个有活的 delete 调用方：{@code CommissionService:100} / {@code CanvasSalesTargetController:330} /
 * {@code SalesOpportunityService:311} / {@code LogisticsResourceServiceImpl:304,356}）。
 *
 * <p>本测试对每个实体真走一次 Spring Data 仓库 bean 的 {@code delete()}，然后 native 读物理行：行在、deleted_at 非空。
 * 变异对照（2026-09-07 实测）：把任一实体的 {@code " AND version = ?"} 去掉 ⇒ 该 case 在 delete() 处
 * {@code Unable to bind parameter #2} 红。
 * ⚠️ H2 表按实体 {@code create}，不证明 prod 列——prod 的 {@code version} 列存在由 validate 起动证明（#669 零剩余失败）。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = {"com.cretas.aims.entity", "com.cretas.aims.logistics.entity"})
@EnableJpaRepositories(basePackages = {"com.cretas.aims.repository", "com.cretas.aims.logistics.repository"})
@DisplayName("@SQLDelete + @Version: 删除 SQL 绑 version 列, repository.delete() 才跑得通且是软删")
class SoftDeleteVersionedEntitiesContractTest {

    /** 2026-09-06 census：全仓「类级 @SQLDelete 且有 @Version」的实体（SqlDeleteBindsVersionColumnContractTest 守住这份清单不漏）。 */
    static Stream<Class<?>> versionedSoftDeleteEntities() {
        return Stream.of(
                com.cretas.aims.entity.CommissionRule.class,
                com.cretas.aims.entity.SalesOpportunity.class,
                com.cretas.aims.entity.rules.BusinessRule.class,
                com.cretas.aims.logistics.entity.LogisticsDailyAvailability.class,
                com.cretas.aims.logistics.entity.LogisticsStoreMaster.class);
    }

    @Autowired EntityManager em;
    @Autowired ApplicationContext ctx;

    @ParameterizedTest(name = "{0}")
    @MethodSource("versionedSoftDeleteEntities")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void repositoryDeleteBindsVersionAndSoftDeletes(Class<?> entityClass) throws Exception {
        String table = entityClass.getAnnotation(Table.class).name();
        Field versionField = versionFieldOf(entityClass);
        assertThat(versionField).as(entityClass.getSimpleName() + " 应有 @Version 字段（否则不属于本清单）").isNotNull();

        em.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate();
        try {
            Object entity = minimalRow(entityClass);
            em.persist(entity);
            em.flush();
            Object id = idOf(entity);
            // 实体保持 managed 直接 delete（不 clear 再 em.find 重载：hypersistence 的 Map jsonb 列在 H2 上读不回来，
            // "{}" cannot be transformed to Json object——H2 仪器限制，与被守行为无关）
            CrudRepository crud = (CrudRepository) new Repositories(ctx).getRepositoryFor(entityClass).orElseThrow();
            crud.delete(entity);           // SQL 缺 version 参数时在这里炸: Unable to bind parameter #2
            em.flush();
            em.clear();

            Object[] row = (Object[]) em.createNativeQuery(
                            "SELECT count(*), max(deleted_at) FROM " + table + " WHERE id = :id")
                    .setParameter("id", id).getSingleResult();
            assertThat(((Number) row[0]).longValue()).as(entityClass.getSimpleName() + " physical_rows").isEqualTo(1L);
            assertThat(row[1]).as(entityClass.getSimpleName() + " deleted_at 应非空").isNotNull();
            Optional<?> after = (Optional<?>) crud.findById(id);
            assertThat(after).as("软删后 findById 应为空").isEmpty();
        } finally {
            em.createNativeQuery("SET REFERENTIAL_INTEGRITY TRUE").executeUpdate();
        }
    }

    // ---- helpers（与 SoftDeleteBatch1ContractTest 同一套反射造行；两个 PR 各自独立，故各留一份）----

    private static Field versionFieldOf(Class<?> c) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) if (f.getAnnotation(Version.class) != null) return f;
        }
        return null;
    }

    private Object minimalRow(Class<?> c) throws Exception {
        var ctor = c.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object o = ctor.newInstance();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                if (f.getAnnotation(Id.class) != null) {
                    if (f.getAnnotation(GeneratedValue.class) == null && f.get(o) == null) f.set(o, sample(f.getType(), 36));
                    continue;
                }
                if (f.getAnnotation(Version.class) != null) {
                    if (f.get(o) == null) f.set(o, f.getType() == Integer.class || f.getType() == int.class ? 0 : 0L);
                    continue;
                }
                Column col = f.getAnnotation(Column.class);
                JoinColumn jc = f.getAnnotation(JoinColumn.class);
                boolean required = (col != null && !col.nullable()) || (jc != null && !jc.nullable());
                if (required && f.get(o) == null) {
                    Object v = sample(f.getType(), col != null ? col.length() : 255);
                    if (v == null) throw new IllegalStateException(c.getSimpleName() + "." + f.getName()
                            + " 是 NOT NULL 但本测试不会造 " + f.getType().getSimpleName());
                    f.set(o, v);
                }
            }
        }
        return o;
    }

    private static Object sample(Class<?> t, int length) {
        if (t == String.class) {
            String v = "sv-" + UUID.randomUUID().toString().substring(0, 8);
            return length < v.length() ? v.substring(0, Math.max(1, length)) : v;
        }
        if (t == Long.class || t == long.class) return 1L;
        if (t == Integer.class || t == int.class) return 1;
        if (t == Boolean.class || t == boolean.class) return Boolean.TRUE;
        if (t == BigDecimal.class) return BigDecimal.ONE;
        if (t == Double.class || t == double.class) return 1.0d;
        if (t == LocalDate.class) return LocalDate.of(2026, 9, 7);
        if (t == LocalDateTime.class) return LocalDateTime.of(2026, 9, 7, 12, 0);
        if (t == UUID.class) return UUID.randomUUID();
        if (t.isEnum()) return t.getEnumConstants()[0];
        if (t == java.util.List.class) return new ArrayList<>(List.of(new java.util.HashMap<>(java.util.Map.of("k", "v"))));
        if (t == java.util.Map.class) return new java.util.HashMap<>(java.util.Map.of("k", "v"));
        return null;
    }

    private static Object idOf(Object entity) throws Exception {
        for (Class<?> k = entity.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (f.getAnnotation(Id.class) != null) { f.setAccessible(true); return f.get(entity); }
            }
        }
        throw new IllegalStateException("no @Id on " + entity.getClass());
    }
}
