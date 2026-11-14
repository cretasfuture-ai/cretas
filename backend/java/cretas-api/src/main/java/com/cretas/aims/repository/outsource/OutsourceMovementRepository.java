package com.cretas.aims.repository.outsource;

import com.cretas.aims.entity.outsource.OutsourceMovement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OutsourceMovementRepository extends JpaRepository<OutsourceMovement, String> {

    List<OutsourceMovement> findByOrderIdOrderByCreatedAtAsc(String orderId);

    List<OutsourceMovement> findByLineIdAndMovementTypeIn(String lineId, Collection<OutsourceMovement.Type> types);

    Optional<OutsourceMovement> findFirstByFactoryIdAndClientRequestId(String factoryId, String clientRequestId);
}
