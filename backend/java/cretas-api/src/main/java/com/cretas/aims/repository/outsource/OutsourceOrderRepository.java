package com.cretas.aims.repository.outsource;

import com.cretas.aims.entity.outsource.OutsourceOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;

public interface OutsourceOrderRepository extends JpaRepository<OutsourceOrder, String> {

    Optional<OutsourceOrder> findByIdAndFactoryId(String id, String factoryId);

    /** 所有收发动作先锁委外单: 同一张单的动作串行, 结单与收发不会交错。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM OutsourceOrder o WHERE o.id = :id AND o.factoryId = :factoryId")
    Optional<OutsourceOrder> findByIdAndFactoryIdForUpdate(@Param("id") String id, @Param("factoryId") String factoryId);

    @Query("SELECT o FROM OutsourceOrder o WHERE o.factoryId = :factoryId "
            + "AND (CAST(:supplierId AS string) IS NULL OR o.supplierId = :supplierId) "
            + "AND (:statusesEmpty = true OR o.status IN :statuses) "
            + "AND (CAST(:keyword AS string) IS NULL OR LOWER(o.orderNo) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%'))) "
            + "ORDER BY o.createdAt DESC")
    Page<OutsourceOrder> search(@Param("factoryId") String factoryId,
                                @Param("supplierId") String supplierId,
                                @Param("statuses") Collection<OutsourceOrder.Status> statuses,
                                @Param("statusesEmpty") boolean statusesEmpty,
                                @Param("keyword") String keyword,
                                Pageable pageable);

    long countByFactoryIdAndOrderNoStartingWith(String factoryId, String prefix);

    boolean existsByFactoryIdAndOrderNo(String factoryId, String orderNo);
}
