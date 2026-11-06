package com.cretas.aims.service;

import com.cretas.aims.dto.decision.DecisionHistoryPage;
import com.cretas.aims.entity.DecisionAuditLog;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.DecisionAuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DecisionHistoryQueryContractTest {
    private final DecisionAuditLogRepository repository = mock(DecisionAuditLogRepository.class);
    private final DecisionHistoryQueryService service = new DecisionHistoryQueryService(repository);

    @Test void boundedFactoryQueryPreservesRealPaginationAndDeterministicTieBreak() {
        when(repository.findByFactoryIdOrderByCreatedAtDesc(eq("F006"), any()))
                .thenReturn(new PageImpl<>(List.of(row()), PageRequest.of(1, 20), 41));
        DecisionHistoryPage result = service.list("F006", 1, 20);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.pageSize()).isEqualTo(20);
        assertThat(result.totalElements()).isEqualTo(41);
        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.records()).extracting(DecisionHistoryPage.Entry::id).containsExactly("decision-1");
        var pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByFactoryIdOrderByCreatedAtDesc(eq("F006"), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getSort().getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.DESC);
        verifyNoMoreInteractions(repository);
    }

    @Test void metadataSerializationHasOnlyAllowlistedFieldsAndNoSnapshotsOrPeople() throws Exception {
        when(repository.findByFactoryIdOrderByCreatedAtDesc(eq("F006"), any()))
                .thenReturn(new PageImpl<>(List.of(row())));
        var json = new ObjectMapper().findAndRegisterModules().valueToTree(service.list("F006", 0, 20));
        var names = new ArrayList<String>();
        json.get("records").get(0).fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrder("id", "decisionType", "decisionCode", "entityType",
                "entityId", "ruleConfigId", "ruleConfigVersion", "createdAt");
        assertThat(json.get("records").get(0).get("decisionType").asText()).isEqualTo("APPROVAL");
        assertThat(json.toString()).doesNotContain("PRIVATE-SECRET", "inputContext", "outputResult", "replayData",
                "checksum", "executorName", "approverName", "reason", "decisionMade");
        verify(repository).findByFactoryIdOrderByCreatedAtDesc(eq("F006"), any());
        verifyNoMoreInteractions(repository);
    }

    @Test void absentOptionalMetadataStaysUnknownRatherThanAnInventedVersionOrDate() {
        var row = row(); row.setRuleConfigId(null); row.setRuleConfigVersion(null);
        row.setCreatedAt(null); row.setDecisionCode(null);
        when(repository.findByFactoryIdOrderByCreatedAtDesc(eq("F006"), any()))
                .thenReturn(new PageImpl<>(List.of(row)));
        var record = service.list("F006", 0, 20).records().getFirst();
        assertThat(record.ruleConfigId()).isNull(); assertThat(record.ruleConfigVersion()).isNull();
        assertThat(record.createdAt()).isNull(); assertThat(record.decisionCode()).isNull();
    }

    @Test void emptyHistoryRemainsAnEmptyPage() {
        when(repository.findByFactoryIdOrderByCreatedAtDesc(eq("F006"), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        var result = service.list("F006", 0, 20);
        assertThat(result.records()).isEmpty(); assertThat(result.totalElements()).isZero();
        assertThat(result.totalPages()).isZero();
    }

    @Test void invalidFactoryOrPaginationNeverQueriesAuditStorage() {
        for (String factory : new String[]{null, "", " "}) {
            assertThatThrownBy(() -> service.list(factory, 0, 20)).isInstanceOf(BusinessException.class);
        }
        for (int[] pair : new int[][]{{-1, 20}, {100001, 20}, {0, 0}, {0, -1}, {0, 51}}) {
            assertThatThrownBy(() -> service.list("F006", pair[0], pair[1])).isInstanceOf(BusinessException.class);
        }
        verifyNoInteractions(repository);
    }

    @Test void aForeignOrInvalidRepositoryRowFailsClosedInsteadOfLeakingOrSkipping() {
        var foreign = row(); foreign.setFactoryId("F001");
        when(repository.findByFactoryIdOrderByCreatedAtDesc(eq("F006"), any()))
                .thenReturn(new PageImpl<>(List.of(foreign)));
        assertThatThrownBy(() -> service.list("F006", 0, 20)).isInstanceOf(IllegalStateException.class)
                .hasMessage("决策历史记录的工厂或标识无效");
        var invalid = row(); invalid.setDecisionType(null);
        when(repository.findByFactoryIdOrderByCreatedAtDesc(eq("F006"), any()))
                .thenReturn(new PageImpl<>(List.of(invalid)));
        assertThatThrownBy(() -> service.list("F006", 0, 20)).isInstanceOf(IllegalStateException.class);
    }

    private static DecisionAuditLog row() {
        return DecisionAuditLog.builder().id("decision-1").factoryId("F006")
                .decisionType(DecisionAuditLog.DecisionType.APPROVAL).decisionCode("QA-DECISION-1")
                .entityType("QualityInspection").entityId("inspection-1")
                .ruleConfigId("rule-1").ruleConfigVersion(2).createdAt(LocalDateTime.of(2026, 11, 1, 12, 0))
                .inputContext("PRIVATE-SECRET").outputResult("PRIVATE-SECRET").replayData("PRIVATE-SECRET")
                .checksum("PRIVATE-SECRET").executorName("PRIVATE-SECRET").approverName("PRIVATE-SECRET")
                .reason("PRIVATE-SECRET").decisionMade("PRIVATE-SECRET").build();
    }
}
