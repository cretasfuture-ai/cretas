package com.cretas.aims.audit.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded, independent writer so a busy audit database cannot block business requests. */
@Service
@Slf4j
public class ActivityEventStore {
    private static final int CAPACITY = 20_000;
    private static final int BATCH_SIZE = 200;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final ArrayBlockingQueue<ActivityEvent> queue = new ArrayBlockingQueue<>(CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean running;
    private Thread worker;

    public ActivityEventStore(JdbcTemplate jdbc, ObjectMapper objectMapper,
            @Qualifier("primaryTransactionManager") PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public boolean submit(ActivityEvent event) {
        if (event == null) return false;
        if (!queue.offer(event)) {
            long count = dropped.incrementAndGet();
            if (count == 1 || count % 1000 == 0) log.error("activity_events_queue_full dropped={}", count);
            return false;
        }
        return true;
    }

    @PostConstruct
    void start() {
        running = true;
        worker = new Thread(this::run, "activity-event-writer");
        worker.setDaemon(true);
        worker.start();
    }

    @PreDestroy
    void stop() {
        running = false;
        if (worker != null) worker.interrupt();
        drain();
    }

    private void run() {
        while (running) {
            try {
                ActivityEvent first = queue.poll(1, TimeUnit.SECONDS);
                if (first == null) continue;
                List<ActivityEvent> batch = new ArrayList<>(BATCH_SIZE);
                batch.add(first);
                queue.drainTo(batch, BATCH_SIZE - 1);
                insert(batch);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception failure) {
                log.error("activity_events_writer_failed type={}", failure.getClass().getSimpleName());
            }
        }
    }

    private void drain() {
        List<ActivityEvent> batch = new ArrayList<>(BATCH_SIZE);
        while (!queue.isEmpty()) {
            queue.drainTo(batch, BATCH_SIZE);
            insert(batch);
            batch.clear();
        }
    }

    private void insert(List<ActivityEvent> batch) {
        if (batch.isEmpty()) return;
        try {
            transactions.executeWithoutResult(status -> jdbc.batchUpdate("INSERT INTO activity_events "
                    + "(event_id,factory_id,user_id,source,action,route,detail,correlation_id,client_action_id,client,occurred_at) "
                    + "VALUES (?,?,?,?,?,?,CAST(? AS jsonb),?,?,?,?) "
                    + "ON CONFLICT (factory_id,user_id,event_id) DO NOTHING",
                    batch, BATCH_SIZE, (statement, event) -> {
                statement.setString(1, event.eventId());
                statement.setString(2, event.factoryId());
                statement.setLong(3, event.userId());
                statement.setString(4, event.source());
                statement.setString(5, event.action());
                statement.setString(6, event.route());
                statement.setString(7, json(event.detail()));
                statement.setString(8, event.correlationId());
                statement.setString(9, event.clientActionId());
                statement.setString(10, event.client());
                statement.setObject(11, event.occurredAt().atOffset(ZoneOffset.UTC));
            }));
        } catch (Exception failure) {
            log.error("activity_events_insert_failed count={} type={}", batch.size(), failure.getClass().getSimpleName());
        }
    }

    private String json(Map<String, Object> detail) {
        try {
            return objectMapper.writeValueAsString(detail == null ? Map.of() : detail);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid activity detail", failure);
        }
    }

    public List<Map<String, Object>> find(String factoryId, Long userId, String source,
            String correlationId, OffsetDateTime start, OffsetDateTime end, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT id,factory_id,user_id,source,action,route,detail::text AS detail,"
                + "correlation_id,client_action_id,client,"
                + "to_char(occurred_at AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"') AS occurred_at,"
                + "to_char(received_at AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"') AS received_at "
                + "FROM activity_events WHERE factory_id=?");
        List<Object> parameters = new ArrayList<>();
        parameters.add(factoryId);
        if (userId != null) { sql.append(" AND user_id=?"); parameters.add(userId); }
        if (source != null) { sql.append(" AND source=?"); parameters.add(source); }
        if (correlationId != null) { sql.append(" AND correlation_id=?"); parameters.add(correlationId); }
        if (start != null) { sql.append(" AND occurred_at>=?"); parameters.add(start); }
        if (end != null) { sql.append(" AND occurred_at<=?"); parameters.add(end); }
        sql.append(" ORDER BY occurred_at DESC,id DESC LIMIT ? OFFSET ?");
        parameters.add(Math.min(Math.max(limit, 1), 200));
        parameters.add(Math.min(Math.max(offset, 0), 10_000));
        return jdbc.queryForList(sql.toString(), parameters.toArray());
    }

    /** Keep the high-volume timeline bounded; durable business audit has its own lifecycle. */
    @Scheduled(cron = "0 * * * * *")
    public void purgeExpired() {
        try {
            int removed = transactions.execute(status -> jdbc.update("DELETE FROM activity_events WHERE id IN "
                    + "(SELECT id FROM activity_events WHERE occurred_at < NOW() - INTERVAL '180 days' "
                    + "ORDER BY occurred_at LIMIT 5000)"));
            if (removed > 0) log.info("activity_events_expired_removed count={}", removed);
        } catch (Exception failure) {
            log.error("activity_events_expiry_failed type={}", failure.getClass().getSimpleName());
        }
    }
}
