package com.cretas.aims.service.skill.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Receipts of Skill runs ({@code skill_run_records}) and the Skills a factory saved from them
 * ({@code factory_saved_skills}). Every read is factory-scoped; there is no lookup by id alone.
 */
@Component
@RequiredArgsConstructor
public class SkillRunStore {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * @param auditEventId null when the step did not run through the tool gateway
     */
    public record Step(String tool, String descriptorVersion, JsonNode params, String gatewayStatus,
            String auditEventId) {
    }

    public record Run(String id, String factoryId, Long userId, String skillName, String savedSkillId,
            String status, List<Step> steps, LocalDateTime createdAt) {

        /** Only a run whose every step succeeded through the gateway can become a reusable Skill. */
        public boolean reusable() {
            return "SUCCEEDED".equals(status) && !steps.isEmpty()
                    && steps.stream().allMatch(step -> step.auditEventId() != null
                            && "SUCCEEDED".equals(step.gatewayStatus()));
        }
    }

    public record SavedStep(String tool, JsonNode params) {
    }

    public record SavedSkill(String id, String factoryId, String name, String displayName, String sourceRunId,
            String sourceSkillName, List<SavedStep> steps, JsonNode parameterContract, boolean enabled,
            Long createdBy, LocalDateTime createdAt) {
    }

    public String recordRun(String factoryId, Long userId, String skillName, String savedSkillId, String status,
            List<Step> steps) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO skill_run_records (id, factory_id, user_id, skill_name, saved_skill_id,"
                        + " status, steps, created_at) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)",
                id, factoryId, userId, skillName, savedSkillId, status, json(steps),
                Timestamp.valueOf(LocalDateTime.now()));
        return id;
    }

    public Optional<Run> findRun(String factoryId, String runId) {
        return jdbcTemplate.query("SELECT id, factory_id, user_id, skill_name, saved_skill_id, status,"
                        + " CAST(steps AS TEXT) AS steps, created_at FROM skill_run_records"
                        + " WHERE id = ? AND factory_id = ?",
                (rs, row) -> new Run(rs.getString("id"), rs.getString("factory_id"), rs.getLong("user_id"),
                        rs.getString("skill_name"), rs.getString("saved_skill_id"), rs.getString("status"),
                        List.of(read(rs.getString("steps"), Step[].class)),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                runId, factoryId).stream().findFirst();
    }

    public SavedSkill saveSkill(String factoryId, String name, String displayName, Run source,
            List<SavedStep> steps, JsonNode contract, Long createdBy) {
        String id = UUID.randomUUID().toString();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbcTemplate.update("INSERT INTO factory_saved_skills (id, factory_id, name, display_name, source_run_id,"
                        + " source_skill_name, steps, parameter_contract, enabled, created_by, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), TRUE, ?, ?, ?)",
                id, factoryId, name, displayName, source.id(), source.skillName(), json(steps), json(contract),
                createdBy, now, now);
        return findSaved(factoryId, id).orElseThrow();
    }

    public Optional<SavedSkill> findSaved(String factoryId, String savedSkillId) {
        return jdbcTemplate.query(SAVED_SELECT + " WHERE id = ? AND factory_id = ?", this::mapSaved,
                savedSkillId, factoryId).stream().findFirst();
    }

    public List<SavedSkill> listSaved(String factoryId) {
        return jdbcTemplate.query(SAVED_SELECT + " WHERE factory_id = ? ORDER BY created_at DESC", this::mapSaved,
                factoryId);
    }

    public int setSavedEnabled(String factoryId, String savedSkillId, boolean enabled) {
        return jdbcTemplate.update("UPDATE factory_saved_skills SET enabled = ?, updated_at = ?"
                        + " WHERE id = ? AND factory_id = ?",
                enabled, Timestamp.valueOf(LocalDateTime.now()), savedSkillId, factoryId);
    }

    private static final String SAVED_SELECT = "SELECT id, factory_id, name, display_name, source_run_id,"
            + " source_skill_name, CAST(steps AS TEXT) AS steps, CAST(parameter_contract AS TEXT) AS contract,"
            + " enabled, created_by, created_at FROM factory_saved_skills";

    private SavedSkill mapSaved(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new SavedSkill(rs.getString("id"), rs.getString("factory_id"), rs.getString("name"),
                rs.getString("display_name"), rs.getString("source_run_id"), rs.getString("source_skill_name"),
                List.of(read(rs.getString("steps"), SavedStep[].class)), read(rs.getString("contract"), JsonNode.class),
                rs.getBoolean("enabled"), rs.getLong("created_by"), rs.getTimestamp("created_at").toLocalDateTime());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("skill run record could not be serialized", e);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("skill run record could not be read", e);
        }
    }
}
