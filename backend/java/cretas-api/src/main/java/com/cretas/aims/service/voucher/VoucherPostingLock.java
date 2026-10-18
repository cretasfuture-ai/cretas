package com.cretas.aims.service.voucher;

import com.cretas.aims.entity.finance.Voucher;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Objects;

/** Lock the scoped scalar row before refreshing its posting status. */
public final class VoucherPostingLock {
    private VoucherPostingLock() { }

    public static boolean lockAndRefresh(EntityManager entityManager, Voucher voucher, String factoryId) {
        if (entityManager == null) throw new IllegalStateException("凭证过账缺少事务锁，拒绝执行");
        if (voucher == null || voucher.getId() == null || factoryId == null
                || !Objects.equals(factoryId, voucher.getFactoryId())) return false;
        List<?> ids = entityManager.createNativeQuery("SELECT id FROM vouchers WHERE id = :id "
                        + "AND factory_id = :factoryId AND deleted_at IS NULL FOR UPDATE")
                .setParameter("id", voucher.getId()).setParameter("factoryId", factoryId).getResultList();
        if (ids.size() != 1 || !Objects.equals(voucher.getId(), String.valueOf(ids.get(0)))) return false;
        entityManager.refresh(voucher);
        return true;
    }
}
