package com.cretas.aims.service.finance;

import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.VoucherRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.YearMonth;

/** Final locking is stricter than opening the existing adjustment window. */
@Service
@RequiredArgsConstructor
public class ClosingReadinessService {
    private final VoucherRepository vouchers;
    private final EntityManager entityManager;

    public void assertReady(String factory,int year,int month) {
        var range=YearMonth.of(year,month);
        long drafts=vouchers.countByFactoryIdAndStatusAndVoucherDateBetweenAndDeletedAtIsNull(
                factory,VoucherStatus.DRAFT,range.atDay(1),range.atEndOfMonth());
        if(drafts>0) throw new BusinessException(409,"本期间还有"+drafts+"张待过账凭证，请先审核过账或作废，再锁账")
                .withCode("CLOSING_PENDING_VOUCHERS");
        long pending=countPendingAdjustments(factory,year,month);
        if(pending>0) throw new BusinessException(409,"本期间还有"+pending+"笔应收应付调整待审核，请先处理后再锁账")
                .withCode("CLOSING_PENDING_ADJUSTMENTS");
    }

    public long countPendingAdjustments(String factory,int year,int month) {
        var range=YearMonth.of(year,month);
        Number pending=(Number)entityManager.createNativeQuery("""
            SELECT count(*) FROM ar_ap_transactions WHERE factory_id=:factory AND deleted_at IS NULL
              AND approval_status='PENDING' AND transaction_date BETWEEN :start AND :end
            """).setParameter("factory",factory).setParameter("start",range.atDay(1))
                .setParameter("end",range.atEndOfMonth()).getSingleResult();
        return pending.longValue();
    }
}
