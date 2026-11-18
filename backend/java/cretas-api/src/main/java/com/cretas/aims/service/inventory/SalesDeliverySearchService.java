package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 发货单检索 (G9): keyword / 订单号 / 状态 / 发货日期范围。
 * 全部条件按需拼接 (没传就不出现在 SQL 里, 避免 PG 对 null 参数的类型推断问题); 恒带 factory_id。
 */
@Service
public class SalesDeliverySearchService {

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    public PageResponse<SalesDeliveryRecord> search(String factoryId, String keyword, String orderNumber,
                                                    SalesDeliveryStatus status, LocalDate fromDate, LocalDate toDate,
                                                    int page, int size) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 200);
        StringBuilder where = new StringBuilder(" WHERE d.factoryId = :f");
        String kw = blankToNull(keyword);
        String on = blankToNull(orderNumber);
        if (kw != null) {
            where.append(" AND (LOWER(d.deliveryNumber) LIKE :kw OR LOWER(d.orderNumber) LIKE :kw"
                    + " OR LOWER(d.customerName) LIKE :kw OR LOWER(d.trackingNumber) LIKE :kw)");
        }
        if (on != null) {
            where.append(" AND LOWER(d.orderNumber) LIKE :on");
        }
        if (status != null) {
            where.append(" AND d.status = :st");
        }
        if (fromDate != null) {
            where.append(" AND d.deliveryDate >= :from");
        }
        if (toDate != null) {
            where.append(" AND d.deliveryDate <= :to");
        }
        var count = em.createQuery("SELECT COUNT(d) FROM SalesDeliveryRecord d" + where, Long.class);
        var rows = em.createQuery("SELECT d FROM SalesDeliveryRecord d" + where
                + " ORDER BY d.createdAt DESC, d.id DESC", SalesDeliveryRecord.class);
        for (var q : List.of(count, rows)) {
            q.setParameter("f", factoryId);
            if (kw != null) q.setParameter("kw", "%" + kw.toLowerCase() + "%");
            if (on != null) q.setParameter("on", "%" + on.toLowerCase() + "%");
            if (status != null) q.setParameter("st", status);
            if (fromDate != null) q.setParameter("from", fromDate);
            if (toDate != null) q.setParameter("to", toDate);
        }
        long total = count.getSingleResult();
        List<SalesDeliveryRecord> content = rows.setFirstResult((p - 1) * s).setMaxResults(s).getResultList();
        return PageResponse.of(content, p, s, total);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
