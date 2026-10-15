package com.cretas.aims.repository.interview;

import com.cretas.aims.entity.interview.FactoryBuildPlanSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 建厂预览快照 —— 复核向导按工厂取回「客户当时说要什么」。
 *
 * <p>⚠️ 同一个工厂可能 apply 多次（客户改了再来一遍），所以取<b>最后一次</b>：
 * 复核要对的是「刚才那次建厂做了什么」，不是第一次。
 *
 * @since 2026-08-17
 */
@Repository
public interface FactoryBuildPlanSnapshotRepository
        extends JpaRepository<FactoryBuildPlanSnapshot, Long> {

    Optional<FactoryBuildPlanSnapshot>
            findFirstByFactoryIdOrderByCreatedAtDesc(String factoryId);
}
