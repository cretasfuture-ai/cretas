package com.cretas.aims.engine;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * S6-04 kill switch for delegated scheduled writes. Two levels, either one stops every delegated
 * write at its next fire, before any grant is consumed:
 * <ul>
 *   <li>the deployment property {@code cretas.autonomous.delegated-writes.enabled} (default true);</li>
 *   <li>a factory's own switch ({@code autonomous_write_switches}), set by its super admin.</li>
 * </ul>
 * One config is stopped by disabling it or revoking its grant. When the factory's switch cannot be
 * read the write is treated as suspended: an unknown switch never lets a write through.
 *
 * <p>The primary pool runs with auto-commit off, so every write here is transactional; a
 * JdbcTemplate write outside a transaction is rolled back when its connection returns to the pool.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutonomousWriteSwitch {

    private final JdbcTemplate jdbcTemplate;

    @Value("${cretas.autonomous.delegated-writes.enabled:true}")
    private boolean deploymentEnabled = true;

    public record State(String factoryId, boolean writesEnabled, String reason, Long changedBy,
            LocalDateTime changedAt) {
    }

    /** @return the reason writes are stopped for this factory, or empty when they may run */
    public Optional<String> suspension(String factoryId) {
        if (!deploymentEnabled) {
            return Optional.of("delegated scheduled writes are switched off for this deployment");
        }
        try {
            Optional<State> state = state(factoryId);
            if (state.isPresent() && !state.get().writesEnabled()) {
                return Optional.of("delegated scheduled writes are switched off for this factory"
                        + (state.get().reason() == null ? "" : ": " + state.get().reason()));
            }
            return Optional.empty();
        } catch (RuntimeException unreadable) {
            log.error("autonomous write switch unreadable for factory {}; treating writes as suspended", factoryId,
                    unreadable);
            return Optional.of("the factory's write switch could not be read; write not attempted");
        }
    }

    public Optional<State> state(String factoryId) {
        List<State> rows = jdbcTemplate.query("SELECT factory_id, writes_enabled, reason, changed_by, changed_at"
                        + " FROM autonomous_write_switches WHERE factory_id = ?",
                (rs, row) -> new State(rs.getString("factory_id"), rs.getBoolean("writes_enabled"),
                        rs.getString("reason"), rs.getLong("changed_by"),
                        rs.getTimestamp("changed_at").toLocalDateTime()),
                factoryId);
        return rows.stream().findFirst();
    }

    @Transactional
    public State set(String factoryId, boolean writesEnabled, String reason, Long actorId, LocalDateTime now) {
        jdbcTemplate.update("INSERT INTO autonomous_write_switches (factory_id, writes_enabled, reason, changed_by,"
                        + " changed_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT (factory_id) DO UPDATE SET"
                        + " writes_enabled = EXCLUDED.writes_enabled, reason = EXCLUDED.reason,"
                        + " changed_by = EXCLUDED.changed_by, changed_at = EXCLUDED.changed_at",
                factoryId, writesEnabled, reason, actorId, Timestamp.valueOf(now));
        return state(factoryId).orElseThrow();
    }
}
