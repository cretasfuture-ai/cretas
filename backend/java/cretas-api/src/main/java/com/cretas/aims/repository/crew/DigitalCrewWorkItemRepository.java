package com.cretas.aims.repository.crew;

import com.cretas.aims.entity.crew.DigitalCrewWorkItem;
import com.cretas.aims.entity.crew.DigitalCrewWorkItem.Status;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DigitalCrewWorkItemRepository extends JpaRepository<DigitalCrewWorkItem, Long> {

    Optional<DigitalCrewWorkItem> findByIdAndFactoryId(Long id, String factoryId);

    List<DigitalCrewWorkItem> findByFactoryIdAndStatusInOrderByCreatedAtDesc(
            String factoryId, Collection<Status> statuses, Pageable pageable);

    List<DigitalCrewWorkItem> findByFactoryIdOrderByCreatedAtDesc(String factoryId, Pageable pageable);

    List<DigitalCrewWorkItem> findByFactoryIdAndMemberKeyOrderByCreatedAtDesc(String factoryId, String memberKey,
                                                                              Pageable pageable);

    /** 确认在途 (CONFIRMING) 超过时限的事项: 对账回收用。 */
    List<DigitalCrewWorkItem> findByFactoryIdAndStatusAndConfirmedAtBefore(
            String factoryId, Status status, LocalDateTime before);

    /** 已交办、且有人预览过 (凭据可能已被别的入口用掉): 对账用。 */
    List<DigitalCrewWorkItem> findByFactoryIdAndStatusAndPreviewTokenIdIsNotNull(String factoryId, Status status);

    /**
     * 本厂按员工 × 状态的件数与额度, 在库里聚合 (不截断、不按查看人过滤: 所有查看人看到同一个数)。
     * 行 = [memberKey, status, count, sum(quotaCost)]。
     */
    @Query("SELECT w.memberKey, w.status, COUNT(w), COALESCE(SUM(w.quotaCost), 0) FROM DigitalCrewWorkItem w"
            + " WHERE w.factoryId = :factoryId GROUP BY w.memberKey, w.status")
    List<Object[]> countByMemberAndStatus(@Param("factoryId") String factoryId);

    /** 本厂 [from, to] 之间 (北京时间真实日期) 消耗的额度, 可按员工; memberKey 传 null = 全部员工。 */
    @Query("SELECT COALESCE(SUM(w.quotaCost), 0) FROM DigitalCrewWorkItem w WHERE w.factoryId = :factoryId"
            + " AND w.resolvedDate >= :from AND w.resolvedDate <= :to"
            + " AND (CAST(:memberKey AS string) IS NULL OR w.memberKey = :memberKey)")
    long sumQuota(@Param("factoryId") String factoryId, @Param("from") java.time.LocalDate from,
                  @Param("to") java.time.LocalDate to, @Param("memberKey") String memberKey);

    /** 同一人对同一员工说的同一句话, 还在等确认的那件 (防重复交办)。 */
    Optional<DigitalCrewWorkItem> findFirstByFactoryIdAndMemberKeyAndCreatedByUserIdAndUserInputAndStatus(
            String factoryId, String memberKey, Long createdByUserId, String userInput, Status status);

    /**
     * 原子认领: 只有本厂、仍是已交办、由本人预览且凭据摘要相同的事项才转「办理中」。返回 1 = 认领到。
     * 两个确认请求并发时只有一个拿到 1, 另一个 0 (不执行)。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE DigitalCrewWorkItem w SET w.status = :confirming, w.confirmedByUserId = :userId,"
            + " w.confirmedByName = :userName, w.confirmedAt = :now, w.updatedAt = :now, w.version = w.version + 1"
            + " WHERE w.id = :id AND w.factoryId = :factoryId AND w.status = :delegated"
            + " AND w.previewedByUserId = :userId AND w.commandDigest = :digest")
    int claimForConfirm(@Param("id") Long id, @Param("factoryId") String factoryId, @Param("userId") Long userId,
                        @Param("userName") String userName, @Param("digest") String digest,
                        @Param("now") LocalDateTime now,
                        @Param("delegated") Status delegated, @Param("confirming") Status confirming);
}
