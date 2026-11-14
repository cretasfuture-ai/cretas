package com.cretas.aims.repository.issue;

import com.cretas.aims.entity.issue.IssueDisposition;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface IssueDispositionRepository extends JpaRepository<IssueDisposition, String> {

    Optional<IssueDisposition> findByFactoryIdAndSourceTypeAndSourceId(
            String factoryId, IssueDisposition.SourceType sourceType, String sourceId);

    /** 本厂已关闭 / 已转待办的处置 (页面据此标状态), 最近处置的在前。 */
    List<IssueDisposition> findByFactoryIdAndStatusInOrderByActedAtDesc(
            String factoryId, Collection<IssueDisposition.Status> statuses, Pageable pageable);

    /** 「待办 › 指派给我」: 本厂、转给我、仍是已转待办的。 */
    List<IssueDisposition> findByFactoryIdAndAssigneeUserIdAndStatusOrderByActedAtDesc(
            String factoryId, Long assigneeUserId, IssueDisposition.Status status, Pageable pageable);
}
