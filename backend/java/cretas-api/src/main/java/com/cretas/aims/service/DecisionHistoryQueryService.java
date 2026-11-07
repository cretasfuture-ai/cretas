package com.cretas.aims.service;

import com.cretas.aims.dto.decision.DecisionHistoryPage;
import com.cretas.aims.entity.DecisionAuditLog;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.DecisionAuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Factory-scoped, bounded metadata reads. This service never executes or updates an audit. */
@Service
@RequiredArgsConstructor
public class DecisionHistoryQueryService {
    private final DecisionAuditLogRepository repository;

    @Transactional(readOnly = true)
    public DecisionHistoryPage list(String factoryId, int page, int size) {
        if (factoryId == null || factoryId.isBlank() || page < 0 || page > 100_000 || size < 1 || size > 50) {
            throw new BusinessException(400, "工厂不能为空，页码应为0至100000，每页应为1至50条");
        }
        // The existing derived query fixes createdAt DESC; id DESC breaks timestamp ties.
        var rows = repository.findByFactoryIdOrderByCreatedAtDesc(factoryId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
        var records = rows.getContent().stream().map(row -> metadata(factoryId, row)).toList();
        return new DecisionHistoryPage(records, page, size, rows.getTotalElements(), rows.getTotalPages());
    }

    private static DecisionHistoryPage.Entry metadata(String factoryId, DecisionAuditLog row) {
        if (row == null || !factoryId.equals(row.getFactoryId()) || row.getId() == null
                || row.getId().isBlank() || row.getDecisionType() == null
                || row.getEntityType() == null || row.getEntityId() == null) {
            throw new IllegalStateException("决策历史记录的工厂或标识无效");
        }
        return new DecisionHistoryPage.Entry(row.getId(), row.getDecisionType(), row.getDecisionCode(),
                row.getEntityType(), row.getEntityId(), row.getRuleConfigId(),
                row.getRuleConfigVersion(), row.getCreatedAt());
    }
}
