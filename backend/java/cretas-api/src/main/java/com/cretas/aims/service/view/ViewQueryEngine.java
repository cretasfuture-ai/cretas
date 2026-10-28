package com.cretas.aims.service.view;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用 JPA Criteria 按已校验的定义执行只读查询。全程不拼接 SQL/JPQL 字符串;
 * factory_id 条件由本类强制加上, 与定义内容无关。
 * 调用方必须先按当前用户权限算出 {@code columns} / {@code filters} 的可见子集。
 */
@Component
public class ViewQueryEngine {

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<Map<String, Object>> run(ViewEntityCatalog.EntitySpec spec, String factoryId,
                                         List<String> columns, List<ViewDefinition.Filter> filters,
                                         ViewDefinition.Sort sort, int limit) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Tuple> q = cb.createTupleQuery();
        Root<?> root = q.from(spec.type());

        List<Selection<?>> selections = new ArrayList<>();
        for (String col : columns) {
            selections.add(root.get(col).alias(col));
        }
        q.multiselect(selections);

        List<Predicate> where = new ArrayList<>();
        where.add(cb.equal(root.get("factoryId"), factoryId));
        for (ViewDefinition.Filter f : filters) {
            Path<?> path = root.get(f.getField());
            Object v = coerce(path.getJavaType(), f.getValue(), f.getField());
            Expression<Comparable> p = (Expression<Comparable>) path;
            switch (f.getOp()) {
                case "eq" -> where.add(cb.equal(path, v));
                case "ne" -> where.add(cb.notEqual(path, v));
                case "contains" -> where.add(cb.like(cb.lower(root.<String>get(f.getField())),
                        "%" + escapeLike(f.getValue().toLowerCase()) + "%", '\\'));
                case "gt" -> where.add(cb.greaterThan(p, (Comparable) v));
                case "gte" -> where.add(cb.greaterThanOrEqualTo(p, (Comparable) v));
                case "lt" -> where.add(cb.lessThan(p, (Comparable) v));
                case "lte" -> where.add(cb.lessThanOrEqualTo(p, (Comparable) v));
                default -> throw new IllegalStateException("未校验的操作符: " + f.getOp());
            }
        }
        q.where(where.toArray(new Predicate[0]));

        if (sort != null) {
            q.orderBy("desc".equals(sort.getDirection())
                    ? cb.desc(root.get(sort.getField())) : cb.asc(root.get(sort.getField())));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Tuple t : em.createQuery(q).setMaxResults(limit).getResultList()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (String col : columns) row.put(col, t.get(col));
            rows.add(row);
        }
        return rows;
    }

    private static Object coerce(Class<?> type, String value, String field) {
        String v = value.trim();
        try {
            if (type == Boolean.class || type == boolean.class) return Boolean.valueOf(v);
            if (type == Integer.class || type == int.class) return new BigDecimal(v).intValueExact();
            if (type == Long.class || type == long.class) return new BigDecimal(v).longValueExact();
            if (type == BigDecimal.class) return new BigDecimal(v);
        } catch (ArithmeticException | NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "字段 " + field + " 需要整数: " + value);
        }
        if (type == String.class) return value;
        throw new IllegalStateException("白名单字段类型未覆盖: " + field + " " + type);
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
