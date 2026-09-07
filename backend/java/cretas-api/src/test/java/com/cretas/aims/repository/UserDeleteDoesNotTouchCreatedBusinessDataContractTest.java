package com.cretas.aims.repository;

import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.EntityManager;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLDelete;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 删除用户**只能**软删用户自己，⛔ 不能碰他建过的业务数据。
 *
 * <p>2026-09-07 之前这条路是坏的，而且坏得有回执：{@code User} 没有类级 {@code @SQLDelete}
 * （{@code @MappedSuperclass} 上那个被 Hibernate 静默忽略）⇒ {@code repository.delete()} 是**物理 DELETE**；
 * 而 {@code User} 的 11 个 {@code @OneToMany} 当时全是 {@code cascade = ALL}，其中 10 个目标实体也没有
 * {@code @SQLDelete} ⇒ 删一个用户会**连带物理删掉**他建的物料类型 / 产品类型 / 供应商 / 客户 / 物料批次 /
 * 领料 / 报工等 10 类业务数据。prod 取证：2026-08-29 11:02:54 真跑过一次（userId=1688），那一行今天
 * count=0 而邻居 1687/1689 都在；日志当时打的却是「(软删, 历史单据保留)」——一张假回执。
 *
 * <p>本测试守两件事，缺一不可：
 * <ol>
 *   <li><b>行为</b>：父删之后，子行**物理仍在**且 {@code deleted_at} 仍为 NULL（阴性对照：一行都不许动）。</li>
 *   <li><b>结构</b>：{@code User} 上任何 {@code @OneToMany} 都不许再出现 {@code CascadeType.ALL / REMOVE}
 *       或 {@code orphanRemoval}——行为只在抽样的三张表上证过，结构断言覆盖全部 11 个集合。</li>
 * </ol>
 *
 * <p>⚠️ 只补 {@code @SQLDelete} 而不去 cascade 是**更坏**的中间态：级联会从「物理删 10 类」变成
 * 「软删 10 类」，数据不是没了，是从 {@code @Where} 后面消失，运维查不到也恢复不了。所以两半必须同时成立，
 * 本测试的两条断言正是这两半。
 *
 * <p>变异对照（2026-09-07 实测）：把 {@code createdCustomers} 改回 {@code cascade = CascadeType.ALL}
 * ⇒ 行为断言先红（{@code customers physical_rows} 变 0），结构断言随后点名。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("删用户: 只软删用户自己, 他建过的业务数据一行不动")
class UserDeleteDoesNotTouchCreatedBusinessDataContractTest {

    @Autowired UserRepository userRepository;
    @Autowired EntityManager em;

    @Test
    @DisplayName("行为: userRepository.delete() 之后 users 行仍在且 deleted_at 非空, 他建的三类业务数据一行不变")
    void deletingUserSoftDeletesOnlyTheUserRow() throws Exception {
        em.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate();
        try {
            User user = minimalRow(User.class);
            user.setUsername("sd-user-" + UUID.randomUUID().toString().substring(0, 8));
            em.persist(user);
            em.flush();
            Long userId = user.getId();
            assertThat(userId).isNotNull();

            // 他建过的三类业务数据（created_by 指向他）
            Object mt = childOf(RawMaterialType.class, userId);
            Object pt = childOf(ProductType.class, userId);
            Object cu = childOf(Customer.class, userId);
            em.flush();

            Object mtId = findIdValue(mt), ptId = findIdValue(pt), cuId = findIdValue(cu);
            assertThat(rowsAlive("raw_material_types", mtId)).as("阳性对照: 删之前物料类型是活的").isEqualTo(1L);
            assertThat(rowsAlive("product_types", ptId)).as("阳性对照: 删之前产品类型是活的").isEqualTo(1L);
            assertThat(rowsAlive("customers", cuId)).as("阳性对照: 删之前客户是活的").isEqualTo(1L);

            // 子挂进父的集合：级联 REMOVE 走的就是它（挂上去仍不许被删，这才是被守的行为）
            attach(user, mt);
            attach(user, pt);
            attach(user, cu);

            userRepository.delete(user);
            em.flush();
            em.clear();

            // ① 用户自己：物理行还在、deleted_at 非空
            Object[] userRow = (Object[]) em.createNativeQuery(
                            "SELECT count(*), max(deleted_at) FROM users WHERE id = :id")
                    .setParameter("id", userId).getSingleResult();
            assertThat(((Number) userRow[0]).longValue()).as("users physical_rows（0 = 物理删了）").isEqualTo(1L);
            assertThat(userRow[1]).as("users.deleted_at 应非空").isNotNull();

            // ② 他建过的东西：一行不变（本 PR 的核心阴性对照）
            assertThat(rowsAlive("raw_material_types", mtId)).as("raw_material_types 一行不许动").isEqualTo(1L);
            assertThat(rowsAlive("product_types", ptId)).as("product_types 一行不许动").isEqualTo(1L);
            assertThat(rowsAlive("customers", cuId)).as("customers 一行不许动").isEqualTo(1L);

            // ③ 确认框承诺的那件事：他建过的单据仍查得到（真跑一次查询，不只看表）
            assertThat(em.createNativeQuery(
                            "SELECT count(*) FROM raw_material_types WHERE created_by = :uid AND deleted_at IS NULL")
                    .setParameter("uid", userId).getSingleResult())
                    .as("按 created_by 仍查得到他建的物料类型")
                    .satisfies(v -> assertThat(((Number) v).longValue()).isEqualTo(1L));
        } finally {
            em.createNativeQuery("SET REFERENTIAL_INTEGRITY TRUE").executeUpdate();
        }
    }

    @Test
    @DisplayName("结构: User 的 11 个 @OneToMany 一个都不许带 CascadeType.ALL / REMOVE / orphanRemoval")
    void noCollectionOnUserCascadesRemoval() {
        // ⚠️ 必须 getDeclaredAnnotation, ⛔ 不是 getAnnotation:
        //    后者会沿继承链往上找, 而 User extends BaseEntity —— **BaseEntity 上就有一句 @SQLDelete**。
        //    这条断言「非空」之所以今天不是恒真的, 承重前提是「@SQLDelete 没有被 @Inherited 标注」
        //    (实测 hibernate-core-6.4.10.Final 的 SQLDelete.class 常量池: Target / Retention / Repeatable,
        //     **无 Inherited**; 阳性对照 Retention 命中 4 次, 所以「Inherited=0」不是解析失败)。
        //    ⇒ 但那是**外部库的属性**, 不是我们守得住的东西。改用 getDeclaredAnnotation 之后,
        //      前提从「依赖 Hibernate 不改这个注解」变成「代码自己说清: 我只认 User 自己声明的那一个」。
        //    📌 与本仓 R68 那个坑同源: @SQLDelete / @Where 挂在 @MappedSuperclass 上**不被继承**,
        //      所以「从父类取到一个」在语义上本来就不该算数。
        assertThat(User.class.getDeclaredAnnotation(SQLDelete.class))
                .as("User **自己**必须有类级 @SQLDelete —— 否则 delete() 是物理删, 上面那条行为断言也就无从谈起; "
                        + "⛔ 从 BaseEntity 继承来的不算(Hibernate 对 @MappedSuperclass 上的 @SQLDelete 静默忽略)")
                .isNotNull();

        List<String> offenders = new ArrayList<>();
        int collections = 0;
        for (Field f : User.class.getDeclaredFields()) {
            OneToMany o = f.getAnnotation(OneToMany.class);
            if (o == null) continue;
            collections++;
            boolean cascadesRemoval = false;
            for (CascadeType c : o.cascade()) {
                if (c == CascadeType.ALL || c == CascadeType.REMOVE) cascadesRemoval = true;
            }
            if (cascadesRemoval || o.orphanRemoval()) {
                offenders.add(f.getName() + "(cascade=" + java.util.Arrays.toString(o.cascade())
                        + ", orphanRemoval=" + o.orphanRemoval() + ")");
            }
        }
        assertThat(collections).as("阳性对照: User 上的 @OneToMany 集合数（2026-09-07 census = 11）")
                .isGreaterThanOrEqualTo(11);
        assertThat(offenders)
                .as("这些集合会让删用户连带删掉他建过的业务数据 —— 只留 PERSIST / MERGE")
                .isEmpty();
    }

    // ---- helpers ----

    private long rowsAlive(String table, Object id) {
        return ((Number) em.createNativeQuery(
                        "SELECT count(*) FROM " + table + " WHERE id = :id AND deleted_at IS NULL")
                .setParameter("id", id).getSingleResult()).longValue();
    }

    /** 造一条 created_by 指向该用户的子行。 */
    private <T> T childOf(Class<T> childClass, Long userId) throws Exception {
        T child = minimalRow(childClass);
        Field fk = findField(childClass, "createdBy");
        fk.set(child, userId);
        em.persist(child);
        return child;
    }

    @SuppressWarnings("unchecked")
    private static void attach(User user, Object child) throws Exception {
        for (Field f : User.class.getDeclaredFields()) {
            if (f.getAnnotation(OneToMany.class) == null) continue;
            if (!(f.getGenericType() instanceof java.lang.reflect.ParameterizedType pt)) continue;
            if (pt.getActualTypeArguments()[0] != child.getClass()) continue;
            f.setAccessible(true);
            java.util.Collection<Object> col = (java.util.Collection<Object>) f.get(user);
            if (col == null) { col = new ArrayList<>(); f.set(user, col); }
            col.add(child);
            return;
        }
        throw new IllegalStateException("User 上没有元素类型为 " + child.getClass().getSimpleName() + " 的 @OneToMany");
    }

    private static Object findIdValue(Object entity) throws Exception {
        for (Class<?> k = entity.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (f.getAnnotation(Id.class) != null) { f.setAccessible(true); return f.get(entity); }
            }
        }
        throw new IllegalStateException("no @Id on " + entity.getClass());
    }

    private static Field findField(Class<?> c, String name) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try { Field f = k.getDeclaredField(name); f.setAccessible(true); return f; } catch (NoSuchFieldException ignored) { }
        }
        throw new IllegalStateException(c.getSimpleName() + " 没有字段 " + name);
    }

    private <T> T minimalRow(Class<T> c) throws Exception {
        var ctor = c.getDeclaredConstructor();
        ctor.setAccessible(true);
        T o = ctor.newInstance();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                if (f.getAnnotation(Id.class) != null) {
                    if (f.getAnnotation(GeneratedValue.class) == null && f.get(o) == null) {
                        f.set(o, sample(f.getType(), f.getAnnotation(Column.class)));
                    }
                    continue;
                }
                Column col = f.getAnnotation(Column.class);
                JoinColumn jc = f.getAnnotation(JoinColumn.class);
                boolean required = (col != null && !col.nullable()) || (jc != null && !jc.nullable() && jc.insertable());
                if (required && f.get(o) == null) {
                    Object v = sample(f.getType(), col);
                    if (v == null) throw new IllegalStateException(c.getSimpleName() + "." + f.getName()
                            + " 是 NOT NULL 但本测试不会造 " + f.getType().getSimpleName());
                    f.set(o, v);
                }
            }
        }
        return o;
    }

    private static Object sample(Class<?> t, Column col) {
        int length = col != null && col.length() > 0 ? col.length() : 255;
        String def = col != null ? col.columnDefinition().toLowerCase() : "";
        if (t == UUID.class) return UUID.randomUUID();
        if (t == String.class) {
            if (def.contains("uuid")) return UUID.randomUUID().toString();
            String v = "sd-" + UUID.randomUUID().toString().substring(0, 8);
            return length < v.length() ? v.substring(0, Math.max(1, length)) : v;
        }
        if (t == Long.class || t == long.class) return 1L;
        if (t == Integer.class || t == int.class) return 1;
        if (t == Boolean.class || t == boolean.class) return Boolean.TRUE;
        if (t == BigDecimal.class) return BigDecimal.ONE;
        if (t == Double.class || t == double.class) return 1.0d;
        if (t == LocalDate.class) return LocalDate.of(2026, 9, 7);
        if (t == LocalDateTime.class) return LocalDateTime.of(2026, 9, 7, 12, 0);
        if (t.isEnum()) return t.getEnumConstants()[0];
        if (t == java.util.List.class) return new ArrayList<>(List.of(new java.util.HashMap<>(java.util.Map.of("k", "v"))));
        if (t == java.util.Map.class) return new java.util.HashMap<>(java.util.Map.of("k", "v"));
        return null;
    }
}
