package com.cretas.aims.service.production;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

/** Terminal output identity comes from the existing settlement, never a directory owner's name. */
@Service
public class FormalFinishedOutputScope {
    // Alias b is the factory-scoped production batch. Legacy plans without named output rows remain visible.
    public static final String SQL_PREDICATE = """
        AND (NOT EXISTS (
            SELECT 1 FROM production_settlements fs JOIN production_settlement_output_lines fo
              ON fo.settlement_id=fs.id AND fo.factory_id=fs.factory_id
              AND fo.production_plan_id=fs.production_plan_id AND fo.deleted_at IS NULL
            WHERE fs.factory_id=b.factory_id AND fs.production_plan_id=b.production_plan_id
              AND fs.deleted_at IS NULL AND NULLIF(TRIM(fo.reported_batch_number),'') IS NOT NULL)
          OR EXISTS (
            SELECT 1 FROM production_settlements fs JOIN production_settlement_output_lines fo
              ON fo.settlement_id=fs.id AND fo.factory_id=fs.factory_id
              AND fo.production_plan_id=fs.production_plan_id AND fo.deleted_at IS NULL
            WHERE fs.factory_id=b.factory_id AND fs.production_plan_id=b.production_plan_id
              AND fs.deleted_at IS NULL AND fo.reported_batch_number=b.batch_number
              AND fo.product_type_id=b.product_type_id))
        """;

    @PersistenceContext private EntityManager entityManager;

    @Transactional(readOnly = true)
    public Set<String> visibleBatchNumbers(String factoryId, List<String> numbers) {
        if (factoryId == null || factoryId.isBlank()) throw new IllegalArgumentException("缺少工厂身份");
        if (numbers == null || numbers.isEmpty()) return Set.of();
        List<?> rows = entityManager.createNativeQuery("""
                SELECT b.batch_number FROM production_batches b
                WHERE b.factory_id=:factory AND b.deleted_at IS NULL AND b.batch_number IN (:numbers)
                """ + SQL_PREDICATE)
                .setParameter("factory", factoryId).setParameter("numbers", numbers).getResultList();
        Set<String> result = new HashSet<>();
        for (Object row : rows) result.add((String) row);
        return Set.copyOf(result);
    }
}
