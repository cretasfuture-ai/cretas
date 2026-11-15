package com.cretas.aims.repository.outsource;

import com.cretas.aims.entity.outsource.OutsourceMovement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OutsourceMovementRepository extends JpaRepository<OutsourceMovement, String> {

    List<OutsourceMovement> findByOrderIdOrderByCreatedAtAsc(String orderId);

    /** 本行全部收发 (按时间)。带 factoryId: 不靠调用方先验过行的租户 (#1755 复审 L5)。 */
    List<OutsourceMovement> findByFactoryIdAndLineIdOrderByCreatedAtAsc(String factoryId, String lineId);

    List<OutsourceMovement> findByFactoryIdAndLineIdInAndMovementTypeIn(String factoryId, Collection<String> lineIds,
                                                                        Collection<OutsourceMovement.Type> types);

    Optional<OutsourceMovement> findFirstByFactoryIdAndClientRequestId(String factoryId, String clientRequestId);
}
