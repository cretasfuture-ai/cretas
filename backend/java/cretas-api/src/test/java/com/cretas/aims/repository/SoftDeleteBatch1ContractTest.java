package com.cretas.aims.repository;

import jakarta.persistence.Column;
import jakarta.persistence.EntityManager;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;
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
 * 软删批 1（brief 卡 B 第 4 条）：19 个「有 repository.delete() 调用方、带类级 @Where、表上只有 pkey 没有业务唯一键」的实体，
 * 补类级 {@code @SQLDelete} 之后，{@code repository.delete()} 必须是软删——物理行仍在、deleted_at 非空、{@code findById} 看不见。
 *
 * <p>为什么要一条条证：{@code BaseEntity(@MappedSuperclass)} 上的 {@code @SQLDelete} 被 Hibernate 静默忽略，
 * 子类不显式写就是物理 DELETE；而两种实现的 {@code findById} 读数完全一样，所以只有看物理行才分得开（#629 的读数）。
 *
 * <p>做法：每个实体从 {@code nullable=false} 元数据反射造一条最小行 → 真正的 Spring Data 仓库 bean {@code delete(entity)}
 * （{@code Repositories.getRepositoryFor}，不是 em.remove 的替身）→ native SQL 读物理行。
 * 变异对照（2026-09-06 实测）：把任一实体的 {@code @SQLDelete} 去掉 ⇒ 该实体那一条 {@code physical_rows=0} 红。
 * ⚠️ 这里的表是 H2 按实体 {@code create} 的，⛔ 不证明 prod 表有 deleted_at 列——那一条由 validate 起动证明（#643/#669）。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("软删批 1: 19 个实体 repository.delete() 必须是软删 (行在 + deleted_at 非空 + findById 看不见)")
class SoftDeleteBatch1ContractTest {

    /** 本批实体（与 PR 正文的 census 一致；加一个就多一条 case，删一个就少一条）。 */
    static Stream<Class<?>> batch1Entities() {
        return Stream.of(
                com.cretas.aims.entity.config.AIReportPromptConfig.class,
                com.cretas.aims.entity.smartbi.AiAgentRule.class,
                com.cretas.aims.entity.Department.class,
                com.cretas.aims.entity.datacenter.ExportRule.class,
                com.cretas.aims.entity.config.FormTemplate.class,
                com.cretas.aims.entity.HourlyRateRule.class,
                com.cretas.aims.entity.datacenter.ImportRule.class,
                com.cretas.aims.entity.Notification.class,
                com.cretas.aims.entity.config.OpinionTemplate.class,
                com.cretas.aims.entity.PieceRateRule.class,
                com.cretas.aims.entity.inventory.PriceList.class,
                com.cretas.aims.entity.ProcessingStageRecord.class,
                com.cretas.aims.entity.ProductionPlanBatchUsage.class,
                com.cretas.aims.entity.inventory.PurchaseOrderItem.class,
                com.cretas.aims.entity.inventory.SalesOrderItem.class,
                com.cretas.aims.entity.scale.ScaleProtocolConfig.class,
                com.cretas.aims.entity.WagePolicy.class,
                com.cretas.aims.entity.WorkProcess.class,
                com.cretas.aims.entity.WorkType.class);
    }

    @Autowired EntityManager em;
    @Autowired ApplicationContext ctx;

    @ParameterizedTest(name = "{0}")
    @MethodSource("batch1Entities")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void repositoryDeleteIsSoft(Class<?> entityClass) throws Exception {
        String table = entityClass.getAnnotation(Table.class).name();

        // H2 按实体 create 出来的外键（factory_id → factories 等）与被守行为无关；本测试只造一张表的一行，
        // 所以在自己的作用域内关掉引用完整性，finally 里恢复（共享 in-mem 库，不能留着关）。
        em.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate();
        try {
            Object id;
            if (hasJsonbCollection(entityClass)) {
                // hypersistence JsonBinaryType 在 H2 上拒绝顶层 JSON 数组 ("[]" cannot be transformed to Json object)，
                // 这两个实体 (ImportRule.mapping / ExportRule.columns) 走 native INSERT 落行，读/删仍走 Hibernate。
                id = nativeInsertMinimalRow(entityClass, table);
            } else {
                Object entity = minimalRow(entityClass);
                em.persist(entity);
                em.flush();
                id = idOf(entity);
            }
            assertThat(id).as("持久化后必须有 id").isNotNull();
            em.clear();

            Optional<Object> repo = new Repositories(ctx).getRepositoryFor(entityClass);
            assertThat(repo).as(entityClass.getSimpleName() + " 的 Spring Data 仓库 bean").isPresent();
            CrudRepository crud = (CrudRepository) repo.get();
            Object managed = em.find(entityClass, id);
            assertThat(managed).as("删除前 findById 能看见").isNotNull();
            crud.delete(managed);
            em.flush();
            em.clear();

            // 被守的行为：物理行还在、deleted_at 非空
            Object[] row = (Object[]) em.createNativeQuery(
                            "SELECT count(*), max(deleted_at) FROM " + table + " WHERE id = :id")
                    .setParameter("id", id).getSingleResult();
            long physicalRows = ((Number) row[0]).longValue();
            assertThat(physicalRows).as(entityClass.getSimpleName() + " physical_rows（0 = 物理删了）").isEqualTo(1L);
            assertThat(row[1]).as(entityClass.getSimpleName() + " deleted_at 应非空").isNotNull();
            // @Where 那一侧：仓库看不见了
            Optional<?> afterDelete = (Optional<?>) crud.findById(id);
            assertThat(afterDelete).as("软删后 findById 应为空").isEmpty();

            // 机制层（放在行为读数之后：变异去掉注解时先红的是 physical_rows=0，而不是这里）
            assertThat(entityClass.getAnnotation(SQLDelete.class)).as(entityClass.getSimpleName() + " 类级 @SQLDelete").isNotNull();
            assertThat(entityClass.getAnnotation(Where.class)).as(entityClass.getSimpleName() + " 类级 @Where").isNotNull();
            assertThat(entityClass.getAnnotation(SQLDelete.class).sql())
                    .as("@SQLDelete 必须打在自己的表上")
                    .startsWith("UPDATE " + table + " SET deleted_at = NOW() WHERE id = ?");
        } finally {
            em.createNativeQuery("SET REFERENTIAL_INTEGRITY TRUE").executeUpdate();
        }
    }

    // ---- 父删级联：cascade=ALL/orphanRemoval 触达的三张子表，父 repository.delete() 之后子行必须是软删 ----

    @org.junit.jupiter.api.Test
    @DisplayName("PurchaseOrder.items (cascade=ALL, orphanRemoval): 父删 ⇒ purchase_order_items 行在且 deleted_at 非空")
    void purchaseOrderDeleteSoftDeletesItems() throws Exception {
        parentDeleteCascadesSoft(com.cretas.aims.entity.inventory.PurchaseOrder.class,
                com.cretas.aims.entity.inventory.PurchaseOrderItem.class, "purchaseOrderId");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("SalesOrder.items (cascade=ALL, orphanRemoval): 父删 ⇒ sales_order_items 行在且 deleted_at 非空")
    void salesOrderDeleteSoftDeletesItems() throws Exception {
        parentDeleteCascadesSoft(com.cretas.aims.entity.inventory.SalesOrder.class,
                com.cretas.aims.entity.inventory.SalesOrderItem.class, "salesOrderId");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("ProductionPlan.batchUsages (cascade=ALL): 父删 ⇒ production_plan_batch_usages 行在且 deleted_at 非空")
    void productionPlanDeleteSoftDeletesBatchUsages() throws Exception {
        parentDeleteCascadesSoft(com.cretas.aims.entity.ProductionPlan.class,
                com.cretas.aims.entity.ProductionPlanBatchUsage.class, "productionPlanId");
    }

    /**
     * 父实体与子实体各造一条最小行（子行的 FK 列指向父 id），父走真仓库 bean delete()。
     * 子实体挂进父的 @OneToMany 集合（级联 REMOVE / orphanRemoval 走的就是它），父保持 managed 直接 delete；
     * 子实体带 @SQLDelete ⇒ 子行应被 UPDATE 成软删，而不是被物理 DELETE。
     * 父自己是物理删（PurchaseOrder/SalesOrder 仍在棘轮清单）还是软删（ProductionPlan #629）与本断言无关，分别读出来登记。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void parentDeleteCascadesSoft(Class<?> parentClass, Class<?> childClass, String childFkField) throws Exception {
        String childTable = childClass.getAnnotation(Table.class).name();
        String parentTable = parentClass.getAnnotation(Table.class).name();
        em.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate();
        try {
            Object parent = minimalRow(parentClass);
            em.persist(parent);
            em.flush();
            Object parentId = idOf(parent);

            Object child = minimalRow(childClass);
            Field fk = findField(childClass, childFkField);
            fk.set(child, parentId instanceof String s ? s : String.valueOf(parentId));
            em.persist(child);
            em.flush();
            Object childId = idOf(child);
            // 父保持 managed、子挂进父的集合字段：级联 REMOVE 走的就是这个集合。
            // （不 clear 再 em.find 重载父：ProductionPlan 的 hypersistence Map<String,String> jsonb 列在 H2 上读不回来——
            //   "{}" cannot be transformed to Json object，那是 H2 上的仪器限制，与被守行为无关。）
            attachToParentCollection(parent, child);

            long childBefore = ((Number) em.createNativeQuery("SELECT count(*) FROM " + childTable + " WHERE id = :id AND deleted_at IS NULL")
                    .setParameter("id", childId).getSingleResult()).longValue();
            assertThat(childBefore).as("阳性对照：删父之前子行是活的").isEqualTo(1L);

            CrudRepository crud = (CrudRepository) new Repositories(ctx).getRepositoryFor(parentClass).orElseThrow();
            crud.delete(parent);
            em.flush();
            em.clear();

            Object[] childRow = (Object[]) em.createNativeQuery(
                            "SELECT count(*), max(deleted_at) FROM " + childTable + " WHERE id = :id")
                    .setParameter("id", childId).getSingleResult();
            assertThat(((Number) childRow[0]).longValue())
                    .as(childClass.getSimpleName() + " 子行 physical_rows（0 = 父删把子行物理删了）").isEqualTo(1L);
            assertThat(childRow[1]).as(childClass.getSimpleName() + " 子行 deleted_at 应非空").isNotNull();

            long parentRows = ((Number) em.createNativeQuery("SELECT count(*) FROM " + parentTable + " WHERE id = :id")
                    .setParameter("id", parentId).getSingleResult()).longValue();
            // 只登记不断言：父是物理删还是软删由父自己的注解决定（见 javadoc）
            System.out.println("CASCADE_READING parent=" + parentClass.getSimpleName() + " parent_physical_rows=" + parentRows
                    + " child=" + childClass.getSimpleName() + " child_physical_rows=1 child_deleted_at=" + childRow[1]);
        } finally {
            em.createNativeQuery("SET REFERENTIAL_INTEGRITY TRUE").executeUpdate();
        }
    }

    /** 把子实体加进父实体上那个元素类型为子类的 @OneToMany 集合（级联 REMOVE / orphanRemoval 走的就是它）。 */
    @SuppressWarnings("unchecked")
    private static void attachToParentCollection(Object parent, Object child) throws Exception {
        for (Class<?> k = parent.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (f.getAnnotation(jakarta.persistence.OneToMany.class) == null) continue;
                if (!(f.getGenericType() instanceof java.lang.reflect.ParameterizedType pt)) continue;
                if (pt.getActualTypeArguments()[0] != child.getClass()) continue;
                f.setAccessible(true);
                java.util.Collection<Object> col = (java.util.Collection<Object>) f.get(parent);
                if (col == null) { col = new ArrayList<>(); f.set(parent, col); }
                col.add(child);
                return;
            }
        }
        throw new IllegalStateException(parent.getClass().getSimpleName() + " 上没有元素类型为 " + child.getClass().getSimpleName() + " 的 @OneToMany");
    }

    private static Field findField(Class<?> c, String name) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try { Field f = k.getDeclaredField(name); f.setAccessible(true); return f; } catch (NoSuchFieldException ignored) { }
        }
        throw new IllegalStateException(c.getSimpleName() + " 没有字段 " + name);
    }

    // ---- 反射造最小行 ----

    private Object minimalRow(Class<?> c) throws Exception {
        var ctor = c.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object o = ctor.newInstance();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                boolean isId = f.getAnnotation(Id.class) != null;
                if (isId) {
                    if (f.getAnnotation(GeneratedValue.class) == null && f.get(o) == null) {
                        f.set(o, sample(f.getType()));      // 应用侧发号的 String id
                    }
                    continue;
                }
                Column col = f.getAnnotation(Column.class);
                JoinColumn jc = f.getAnnotation(JoinColumn.class);
                boolean required = (col != null && !col.nullable()) || (jc != null && !jc.nullable());
                if (required && f.get(o) == null) {
                    int len = col != null ? col.length() : 255;
                    Object v = sample(f.getType(), len);
                    if (v == null) throw new IllegalStateException(c.getSimpleName() + "." + f.getName()
                            + " 是 NOT NULL 但本测试不会造 " + f.getType().getSimpleName() + " —— 给它加一个样例值");
                    f.set(o, v);
                }
            }
        }
        return o;
    }

    private static Object sample(Class<?> t, int length) {
        if (t == String.class) {
            String v = "sd-" + UUID.randomUUID().toString().substring(0, 8);   // 11 字符
            return length < v.length() ? v.substring(0, Math.max(1, length)) : v;
        }
        if (t == Long.class || t == long.class) return 1L;
        if (t == Integer.class || t == int.class) return 1;
        if (t == Boolean.class || t == boolean.class) return Boolean.TRUE;
        if (t == BigDecimal.class) return BigDecimal.ONE;
        if (t == Double.class || t == double.class) return 1.0d;
        if (t == LocalDate.class) return LocalDate.of(2026, 9, 6);
        if (t == LocalDateTime.class) return LocalDateTime.of(2026, 9, 6, 12, 0);
        if (t.isEnum()) return t.getEnumConstants()[0];
        // jsonb 列 (hypersistence JsonBinaryType) 在 H2 上拒绝空数组 "[]"，给一个非空对象元素
        if (t == java.util.List.class) return new ArrayList<>(List.of(new java.util.HashMap<>(java.util.Map.of("k", "v"))));
        if (t == java.util.Map.class) return new java.util.HashMap<>(java.util.Map.of("k", "v"));
        return null;
    }

    private static Object sample(Class<?> t) {
        return sample(t, 255);
    }

    private static boolean isJsonbCollection(Field f) {
        Column col = f.getAnnotation(Column.class);
        boolean jsonb = col != null && col.columnDefinition() != null && col.columnDefinition().toLowerCase().contains("json");
        return jsonb && (java.util.Collection.class.isAssignableFrom(f.getType()) || java.util.Map.class.isAssignableFrom(f.getType()));
    }

    private static boolean hasJsonbCollection(Class<?> c) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (isJsonbCollection(f)) return true;
            }
        }
        return false;
    }

    /** 只用于 jsonb 集合实体：按 NOT NULL 元数据拼一条 native INSERT，jsonb 列给 '[]'，返回自增 id。 */
    private Object nativeInsertMinimalRow(Class<?> c, String table) throws Exception {
        List<String> cols = new ArrayList<>();
        List<String> exprs = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        Object assignedId = null;
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.getAnnotation(Id.class) != null) {
                    if (f.getAnnotation(GeneratedValue.class) != null) continue;   // 自增 id 由库发
                    Column idc = f.getAnnotation(Column.class);
                    assignedId = sample(f.getType(), idc != null ? idc.length() : 255); // 应用侧发号的 id
                    cols.add(idc != null && !idc.name().isEmpty() ? idc.name() : f.getName());
                    exprs.add("?" + (params.size() + 1));
                    params.add(assignedId);
                    continue;
                }
                Column col = f.getAnnotation(Column.class);
                if (col == null || col.nullable()) continue;
                String name = col.name().isEmpty() ? f.getName() : col.name();
                cols.add(name);
                if (isJsonbCollection(f)) {
                    exprs.add("'[]' FORMAT JSON");
                } else {
                    exprs.add("?" + (params.size() + 1));
                    Object v = sample(f.getType(), col.length());
                    if (v instanceof Enum<?> e) v = e.name();
                    params.add(v);
                }
            }
        }
        // BaseEntity 的 created_at / updated_at 在 native 路径上不会被 @PrePersist 填
        if (!cols.contains("created_at")) { cols.add("created_at"); exprs.add("CURRENT_TIMESTAMP"); }
        if (!cols.contains("updated_at")) { cols.add("updated_at"); exprs.add("CURRENT_TIMESTAMP"); }
        var q = em.createNativeQuery("INSERT INTO " + table + " (" + String.join(",", cols) + ") VALUES (" + String.join(",", exprs) + ")");
        for (int i = 0; i < params.size(); i++) q.setParameter(i + 1, params.get(i));
        assertThat(q.executeUpdate()).isEqualTo(1);
        if (assignedId != null) return assignedId;
        Object id = em.createNativeQuery("SELECT max(id) FROM " + table).getSingleResult();
        return id instanceof Number n ? Long.valueOf(n.longValue()) : id;
    }

    private static Object idOf(Object entity) throws Exception {
        for (Class<?> k = entity.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (f.getAnnotation(Id.class) != null) { f.setAccessible(true); return f.get(entity); }
            }
        }
        List<String> none = new ArrayList<>();
        throw new IllegalStateException("no @Id on " + entity.getClass() + none);
    }
}
