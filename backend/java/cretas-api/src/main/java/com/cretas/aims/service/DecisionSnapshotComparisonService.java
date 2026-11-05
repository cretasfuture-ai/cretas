package com.cretas.aims.service;

import com.cretas.aims.dto.decision.DecisionSnapshotComparison;
import com.cretas.aims.dto.decision.DecisionSnapshotComparison.Status;
import com.cretas.aims.dto.decision.DecisionSnapshotComparison.RecordedOutputRelation;
import com.cretas.aims.entity.DecisionAuditLog;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.repository.DecisionAuditLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.TreeSet;

/** Compares historical evidence without invoking any rule, tool, approval or writer. */
@Service
@RequiredArgsConstructor
public class DecisionSnapshotComparisonService {
    private static final int MAX_SNAPSHOT_CHARS = 262_144;
    private final DecisionAuditLogRepository repository;
    private final ObjectMapper mapper;

    @Transactional(readOnly = true)
    public DecisionSnapshotComparison compare(String factoryId, String decisionId, String baselineId) {
        if (blank(factoryId) || blank(decisionId) || blank(baselineId)) {
            throw new BusinessException(400, "工厂和两条决策ID不能为空");
        }
        DecisionAuditLog current = own(factoryId, decisionId);
        DecisionAuditLog baseline = own(factoryId, baselineId);
        if (decisionId.equals(baselineId) || current.getCreatedAt() == null || baseline.getCreatedAt() == null
                || !baseline.getCreatedAt().isBefore(current.getCreatedAt())) {
            return result(current, baseline, Status.NOT_COMPARABLE, "INVALID_HISTORICAL_BASELINE", null, null);
        }
        if (blank(current.getEntityType()) || blank(current.getEntityId())
                || current.getDecisionType() == null
                || !Objects.equals(current.getEntityType(), baseline.getEntityType())
                || !Objects.equals(current.getEntityId(), baseline.getEntityId())
                || current.getDecisionType() != baseline.getDecisionType()) {
            return result(current, baseline, Status.NOT_COMPARABLE, "BUSINESS_SCOPE_MISMATCH", null, null);
        }
        Snapshot left = snapshot(current);
        Snapshot right = snapshot(baseline);
        if (left == null || right == null) {
            return result(current, baseline, Status.NOT_COMPARABLE, "SNAPSHOT_MISSING_OR_INVALID", null, null);
        }
        if (!left.input().equals(right.input())) {
            return result(current, baseline, Status.NOT_COMPARABLE, "INPUT_MISMATCH", null, null);
        }
        boolean same = left.output().equals(right.output());
        boolean integrityVerified = digest(current.getReplayData()).equalsIgnoreCase(current.getChecksum())
                && digest(baseline.getReplayData()).equalsIgnoreCase(baseline.getChecksum());
        // jsonb may normalize the legacy writer's pre-persistence bytes. A mismatch could also
        // mean tampering: never infer the cause or promote semantic equality to verified evidence.
        return new DecisionSnapshotComparison(current.getId(), baseline.getId(),
                integrityVerified ? (same ? Status.SAME_RESULT : Status.DIFFERENT_RESULT) : Status.NOT_COMPARABLE,
                integrityVerified ? (same ? "RECORDED_OUTPUT_EQUAL" : "RECORDED_OUTPUT_DIFFERENT")
                        : "CHECKSUM_MISMATCH_NO_INTEGRITY_PROOF",
                digest(canonical(left.output()).toString()), digest(canonical(right.output()).toString()),
                current.getRuleConfigId(), current.getRuleConfigVersion(), baseline.getRuleConfigId(),
                baseline.getRuleConfigVersion(), false, integrityVerified,
                same ? RecordedOutputRelation.SAME : RecordedOutputRelation.DIFFERENT,
                "PERSISTED_COLUMNS_SEMANTIC_ONLY");
    }

    private DecisionAuditLog own(String factoryId, String id) {
        // Do not reveal whether a foreign ID exists; scope before parsing snapshots.
        return repository.findByIdAndFactoryId(id, factoryId)
                .orElseThrow(() -> new EntityNotFoundException("决策记录不存在或不可访问"));
    }

    private Snapshot snapshot(DecisionAuditLog row) {
        String json = row.getReplayData();
        if (!Boolean.TRUE.equals(row.getIsReplayable()) || blank(json)
                || json.length() > MAX_SNAPSHOT_CHARS || blank(row.getChecksum())
                || !row.getChecksum().matches("(?i)[0-9a-f]{64}")) {
            return null;
        }
        try {
            JsonNode replay = mapper.readTree(json);
            if (replay == null || !replay.isObject()) return null;
            JsonNode input = replay.get("inputContext");
            JsonNode output = replay.get("outputResult");
            if (input == null || input.isNull() || output == null || output.isNull()
                    || blank(row.getInputContext()) || blank(row.getOutputResult())
                    || row.getInputContext().length() > MAX_SNAPSHOT_CHARS
                    || row.getOutputResult().length() > MAX_SNAPSHOT_CHARS) return null;
            if (!input.equals(mapper.readTree(row.getInputContext()))
                    || !output.equals(mapper.readTree(row.getOutputResult()))) return null;
            return new Snapshot(input, output);
        } catch (JsonProcessingException invalid) {
            return null;
        }
    }

    private static DecisionSnapshotComparison result(DecisionAuditLog current, DecisionAuditLog baseline,
            Status status, String reason, String currentHash, String baselineHash) {
        return new DecisionSnapshotComparison(current.getId(), baseline.getId(), status, reason,
                currentHash, baselineHash, current.getRuleConfigId(), current.getRuleConfigVersion(),
                baseline.getRuleConfigId(), baseline.getRuleConfigVersion(), false, false,
                RecordedOutputRelation.NOT_EVALUATED, "PERSISTED_COLUMNS_SEMANTIC_ONLY");
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    private record Snapshot(JsonNode input, JsonNode output) { }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            var object = mapper.createObjectNode();
            var keys = new TreeSet<String>();
            value.fieldNames().forEachRemaining(keys::add);
            keys.forEach(key -> object.set(key, canonical(value.get(key))));
            return object;
        }
        if (value.isArray()) {
            var array = mapper.createArrayNode();
            value.forEach(item -> array.add(canonical(item)));
            return array;
        }
        return value;
    }
}
