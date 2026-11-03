package com.cretas.aims.repository.learning;

import com.cretas.aims.entity.learning.TrainingSampleSecrecyHold;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 挂起待重判的训练样本: 只有重判任务读 (按挂起先后取一批)。 */
@Repository
public interface TrainingSampleSecrecyHoldRepository extends JpaRepository<TrainingSampleSecrecyHold, Long> {

    @Query("SELECT h FROM TrainingSampleSecrecyHold h ORDER BY h.createdAt ASC, h.id ASC")
    List<TrainingSampleSecrecyHold> findOldest(Pageable page);

    long countByFactoryId(String factoryId);
}
