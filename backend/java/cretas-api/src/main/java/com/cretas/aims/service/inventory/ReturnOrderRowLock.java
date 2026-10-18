package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.inventory.ReturnOrder;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.Objects;

/**
 * Acquires the database row lock used by every return-order write path.
 *
 * <p>Hibernate's follow-on pessimistic lock can read an old version before issuing the
 * lock statement.  Locking the scalar id with native {@code FOR UPDATE} first keeps the
 * row lock and the subsequent version/status refresh in the same transaction.</p>
 */
public final class ReturnOrderRowLock {

    private static final String LOCK_SQL = "SELECT id FROM return_orders "
            + "WHERE id = :returnOrderId AND factory_id = :factoryId "
            + "AND deleted_at IS NULL FOR UPDATE";

    private ReturnOrderRowLock() {
    }

    /**
     * Lock and refresh an already loaded order, scoped to the requested factory.
     *
     * @return {@code true} when the current factory owns a live database row;
     *         {@code false} when the row is absent or outside the factory scope
     */
    public static boolean lockAndRefresh(EntityManager entityManager,
                                         ReturnOrder order,
                                         String factoryId) {
        if (entityManager == null) {
            throw new IllegalStateException("退货写路径缺少 EntityManager，拒绝无锁操作");
        }
        if (order == null || order.getId() == null || factoryId == null || factoryId.isBlank()) {
            return false;
        }
        if (!Objects.equals(factoryId, order.getFactoryId())) {
            return false;
        }

        List<?> lockedIds = entityManager.createNativeQuery(LOCK_SQL)
                .setParameter("returnOrderId", order.getId())
                .setParameter("factoryId", factoryId)
                .getResultList();
        if (lockedIds == null || lockedIds.isEmpty()
                || !Objects.equals(order.getId(), String.valueOf(lockedIds.get(0)))) {
            return false;
        }

        // The native FOR UPDATE already owns the row lock.  A lock mode on refresh would
        // reintroduce Hibernate's follow-on-lock race that this helper is designed to close.
        entityManager.refresh(order);
        return true;
    }
}
