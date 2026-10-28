package com.cretas.aims.service.view;

import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.Supplier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 动态视图可查的实体 / 字段白名单。不在这里的实体、字段一律不能保存、不能执行。
 * 每个实体声明「读它需要的权限(任一)」, 每个字段可再声明额外权限(任一): 缺则该列对此用户不可见。
 */
@Component
public class ViewEntityCatalog {

    public enum FieldType {
        STRING(Set.of("eq", "ne", "contains")),
        NUMBER(Set.of("eq", "ne", "gt", "gte", "lt", "lte")),
        BOOLEAN(Set.of("eq"));

        private final Set<String> ops;

        FieldType(Set<String> ops) {
            this.ops = ops;
        }

        public boolean allows(String op) {
            return ops.contains(op);
        }
    }

    public record FieldSpec(String name, String label, FieldType type, Set<String> anyPermission) {
        public boolean restricted() {
            return !anyPermission.isEmpty();
        }
    }

    public record EntitySpec(String key, String label, Class<?> type, Set<String> readAnyPermission,
                             Map<String, FieldSpec> fields) {
    }

    private static final Set<String> FINANCE_READ = Set.of("finance:read_write", "finance:read");

    private final Map<String, EntitySpec> entities = new LinkedHashMap<>();

    public ViewEntityCatalog() {
        register(new EntitySpec("customers", "客户", Customer.class,
                Set.of("sales:read_write", "sales:read", "finance:read_write", "finance:read"),
                fields(List.of(
                        f("code", "客户编码", FieldType.STRING, null),
                        f("name", "客户名称", FieldType.STRING, null),
                        f("customerType", "客户类型", FieldType.STRING, null),
                        f("industry", "行业", FieldType.STRING, null),
                        f("contactName", "联系人", FieldType.STRING, null),
                        f("phone", "电话", FieldType.STRING, null),
                        f("rating", "评分", FieldType.NUMBER, null),
                        f("isActive", "启用", FieldType.BOOLEAN, null),
                        f("creditLimit", "信用额度", FieldType.NUMBER, FINANCE_READ),
                        f("currentBalance", "当前应收余额", FieldType.NUMBER, FINANCE_READ)))));
        register(new EntitySpec("suppliers", "供应商", Supplier.class,
                Set.of("procurement:read_write", "procurement:read", "finance:read_write", "finance:read"),
                fields(List.of(
                        f("code", "供应商编码", FieldType.STRING, null),
                        f("name", "供应商名称", FieldType.STRING, null),
                        f("contactName", "联系人", FieldType.STRING, null),
                        f("phone", "电话", FieldType.STRING, null),
                        f("businessType", "业务类型", FieldType.STRING, null),
                        f("creditLevel", "信用等级", FieldType.STRING, null),
                        f("deliveryDays", "交货天数", FieldType.NUMBER, null),
                        f("rating", "评分", FieldType.NUMBER, null),
                        f("isActive", "启用", FieldType.BOOLEAN, null),
                        f("creditLimit", "信用额度", FieldType.NUMBER, FINANCE_READ),
                        f("currentBalance", "当前应付余额", FieldType.NUMBER, FINANCE_READ)))));
    }

    private void register(EntitySpec spec) {
        entities.put(spec.key(), spec);
    }

    private static FieldSpec f(String name, String label, FieldType type, Set<String> perms) {
        return new FieldSpec(name, label, type, perms == null ? Set.of() : perms);
    }

    private static Map<String, FieldSpec> fields(List<FieldSpec> specs) {
        Map<String, FieldSpec> m = new LinkedHashMap<>();
        specs.forEach(s -> m.put(s.name(), s));
        return m;
    }

    public Optional<EntitySpec> find(String key) {
        return Optional.ofNullable(key == null ? null : entities.get(key));
    }

    public List<EntitySpec> all() {
        return List.copyOf(entities.values());
    }
}
