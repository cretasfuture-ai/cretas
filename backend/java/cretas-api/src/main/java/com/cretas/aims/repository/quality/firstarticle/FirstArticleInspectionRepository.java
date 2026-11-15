package com.cretas.aims.repository.quality.firstarticle;

import com.cretas.aims.entity.quality.firstarticle.FirstArticleInspection;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 全部查询带 factory_id (A 厂读不到 B 厂的首检单, 由 FirstArticlePostgresContractTest 钉住)。 */
public interface FirstArticleInspectionRepository extends JpaRepository<FirstArticleInspection, Long> {

    Optional<FirstArticleInspection> findByFactoryIdAndId(String factoryId, Long id);

    /** 判定 / 作废前加行锁: 两个人同时判同一张单, 后到的读到已判定状态被拒。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM FirstArticleInspection i WHERE i.factoryId = :factoryId AND i.id = :id")
    Optional<FirstArticleInspection> lockByFactoryIdAndId(@Param("factoryId") String factoryId,
                                                          @Param("id") Long id);

    /** 这些任务上所有非作废的首检单, 新的在前 (调用方取每个任务的第一张 = 当前首检状态)。 */
    default List<FirstArticleInspection> findEffectiveForTasks(String factoryId, Collection<Long> taskIds) {
        return findForTasksExcludingStatus(factoryId, taskIds, FirstArticleInspection.Status.VOID);
    }

    @Query("SELECT i FROM FirstArticleInspection i WHERE i.factoryId = :factoryId"
            + " AND i.workProcessTaskId IN :taskIds AND i.status <> :excluded"
            + " ORDER BY i.requestedAt DESC, i.id DESC")
    List<FirstArticleInspection> findForTasksExcludingStatus(@Param("factoryId") String factoryId,
                                                             @Param("taskIds") Collection<Long> taskIds,
                                                             @Param("excluded") FirstArticleInspection.Status excluded);

    @Query("SELECT i FROM FirstArticleInspection i WHERE i.factoryId = :factoryId"
            + " AND (CAST(:status AS string) IS NULL OR CAST(i.status AS string) = CAST(:status AS string))"
            + " AND (CAST(:taskId AS long) IS NULL OR i.workProcessTaskId = :taskId)")
    Page<FirstArticleInspection> search(@Param("factoryId") String factoryId,
                                        @Param("status") String status,
                                        @Param("taskId") Long taskId,
                                        Pageable pageable);

    /**
     * 本厂指定状态下、工序属于给定集合的工序任务 (「工序首检」表用)。
     * 放在这里而不是 {@code WorkProcessTaskRepository}: 只有首检用, 不动共享仓库。
     */
    @Query("SELECT t FROM WorkProcessTask t WHERE t.factoryId = :factoryId"
            + " AND t.workProcessId IN :workProcessIds AND t.status IN :statuses")
    Page<WorkProcessTask> findTasksRequiringFirstArticle(@Param("factoryId") String factoryId,
                                                         @Param("workProcessIds") Collection<String> workProcessIds,
                                                         @Param("statuses") Collection<WorkProcessTask.Status> statuses,
                                                         Pageable pageable);
}
