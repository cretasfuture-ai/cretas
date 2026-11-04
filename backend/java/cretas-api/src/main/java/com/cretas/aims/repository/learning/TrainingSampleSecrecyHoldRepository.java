package com.cretas.aims.repository.learning;

import com.cretas.aims.entity.learning.TrainingSampleSecrecyHold;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 挂起待重判的训练样本: 只有重判任务读。 */
@Repository
public interface TrainingSampleSecrecyHoldRepository extends JpaRepository<TrainingSampleSecrecyHold, Long> {

    /**
     * 下一批待重判 (PENDING) 的行: 按「最近一次尝试, 没试过的按挂起时间」先后 —— 试过一次判不了的行排到后面,
     * 不会让一批卡住的行一直占着队头 (复审 N4c)。REJECTED 的行不再取。
     */
    default List<TrainingSampleSecrecyHold> findNextPending(Pageable page) {
        return findNextByStatus(TrainingSampleSecrecyHold.ReviewStatus.PENDING, page);
    }

    @Query("SELECT h FROM TrainingSampleSecrecyHold h WHERE h.reviewStatus = :status"
            + " ORDER BY COALESCE(h.lastAttemptAt, h.createdAt) ASC, h.id ASC")
    List<TrainingSampleSecrecyHold> findNextByStatus(
            @org.springframework.data.repository.query.Param("status") TrainingSampleSecrecyHold.ReviewStatus status,
            Pageable page);

    long countByFactoryId(String factoryId);

    long countByFactoryIdAndReviewStatus(String factoryId, TrainingSampleSecrecyHold.ReviewStatus status);
}
