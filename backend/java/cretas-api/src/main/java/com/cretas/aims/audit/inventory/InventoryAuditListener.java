package com.cretas.aims.audit.inventory;

import com.cretas.aims.audit.RequestAuditSupport;
import com.cretas.aims.filter.CorrelationIdFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.persistence.Column;
import org.hibernate.HibernateException;
import org.hibernate.event.spi.*;
import org.hibernate.persister.entity.EntityPersister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAccessor;
import java.util.*;

/**
 * Whitelisted inventory facts, written on Hibernate's own transaction connection.
 * An audit failure fails the stock transaction. No async queue, extra transaction,
 * ORM writes or lazy association traversal is allowed inside these flush callbacks.
 * Bulk/native SQL and external database writers do not produce Hibernate events.
 */
public final class InventoryAuditListener implements PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener {
    private static final Logger LOG = LoggerFactory.getLogger(InventoryAuditListener.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ROOT = "com.cretas.aims.entity.";
    private static final Map<String, Set<String>> FIELDS = Map.of(
            ROOT + "MaterialBatch", fields("factoryId batchNumber materialTypeId productTypeId warehouseId receiptQuantity usedQuantity reservedQuantity quantityUnit status ownership ownerCustomerId sourceDocType sourceDocId sourceSalesOrderId sourceSalesOrderItemId receiptDate productionDate expireDate supplierBatchNumber deletedAt"),
            ROOT + "inventory.FinishedGoodsBatch", fields("factoryId batchNumber productTypeId warehouseId producedQuantity shippedQuantity reservedQuantity unit status ownership ownerCustomerId productionPlanId sourceSalesOrderId sourceSalesOrderItemId productionDate expireDate nominalQuantity counterpartyConfirmed reversalLogId deletedAt"),
            ROOT + "inventory.InternalTransfer", fields("transferNumber transferType sourceFactoryId targetFactoryId sourceWarehouseId targetWarehouseId status productionPlanId requestedBy requestedAt approvedBy approvedAt shippedAt receivedAt confirmedAt deletedAt"),
            ROOT + "inventory.InternalTransferItem", fields("transferId itemType materialTypeId productTypeId quantity receivedQuantity unit sourceBatchId targetBatchId materialPackagingSpecId packageQuantitySnapshot packageUnitSnapshot inventoryBaseUnitSnapshot packageToBaseFactorSnapshot deletedAt"),
            ROOT + "factory.FactoryMaterialRequisition", fields("factoryId requisitionNo productionPlanId sourceWarehouseId targetWarehouseId status requiredDate requestedBy pickedBy pickedAt transferredBy transferredAt receivedBy receivedAt closedBy closedAt outboundTransferId returnTransferId deletedAt"),
            ROOT + "factory.FactoryMaterialRequisitionItem", fields("requisition materialTypeId bomRecipeItemId materialCategory requiredQty inputShareRatio pickedQty issuedQty consumedQty wastageQty returnedQty unit selfSupplied deletedAt"));

    private static Set<String> fields(String names) { return Set.of(names.split(" ")); }

    @Override
    public void onPostInsert(PostInsertEvent event) {
        record(event.getPersister(), event.getId(), null, event.getState(), "CREATE", event.getSession());
    }

    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        if (FIELDS.containsKey(event.getPersister().getEntityName()) && event.getOldState() == null) {
            // Do not invent a before-value for unsupported detached Session.update writes.
            event.getSession().getTransaction().markRollbackOnly();
            throw new HibernateException("库存审计无法取得变更前数据，请重新读取记录后提交");
        }
        record(event.getPersister(), event.getId(), event.getOldState(), event.getState(), "UPDATE", event.getSession());
    }

    @Override
    public void onPostDelete(PostDeleteEvent event) {
        record(event.getPersister(), event.getId(), event.getDeletedState(), null, "DELETE", event.getSession());
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister persister) { return false; }

    private void record(EntityPersister persister, Object id, Object[] before, Object[] after,
                        String action, EventSource session) {
        var allowed = FIELDS.get(persister.getEntityName());
        if (allowed == null) return;
        String type = persister.getMappedClass().getSimpleName();
        Actor actor = Actor.capture();
        try {
            Map<String, Object> oldValue = snapshot(persister, before, allowed, session);
            Map<String, Object> newValue = snapshot(persister, after, allowed, session);
            if (Objects.equals(oldValue, newValue)) return;
            if (oldValue != null) oldValue.put("recordId", String.valueOf(id));
            if (newValue != null) newValue.put("recordId", String.valueOf(id));
            if (!session.isTransactionInProgress()) throw new HibernateException("库存审计必须在业务事务内执行");
            // Keep Hibernate's stock batching intact. Audit and pending stock SQL
            // share this transaction and are rolled back together if either fails.
            session.doWork(connection -> {
                Set<String> oldFactories = factories(connection, type, oldValue);
                Set<String> newFactories = factories(connection, type, newValue);
                Set<String> factories = new LinkedHashSet<>(oldFactories);
                factories.addAll(newFactories);
                if (factories.isEmpty()) throw new SQLException("Inventory audit requires an entity factory");
                for (String factory : factories) {
                    // Moving an entity between tenants must not expose the other tenant's snapshot.
                    Map<String, Object> scopedOld = oldFactories.contains(factory) ? oldValue : null;
                    Map<String, Object> scopedNew = newFactories.contains(factory) ? newValue : null;
                    insert(connection, factory, type, String.valueOf(id), action, scopedOld, scopedNew, actor);
                }
            });
        } catch (RuntimeException exception) {
            session.getTransaction().markRollbackOnly();
            LOG.error("inventory_audit_failed entityType={} action={} correlation={} transaction=rollback_required",
                    type, action, actor.correlation());
            throw new HibernateException("库存审计保存失败，本次库存变更未提交，请稍后重试", exception);
        }
    }

    private static Map<String, Object> snapshot(EntityPersister persister, Object[] state,
                                                Set<String> allowed, EventSource session) {
        if (state == null) return null;
        Map<String, Object> result = new TreeMap<>();
        String[] names = persister.getPropertyNames();
        for (int i = 0; i < names.length; i++) {
            if (!allowed.contains(names[i])) continue;
            if ("requisition".equals(names[i])) {
                // PersistenceUnitUtil handles managed, detached and proxy references without loading the parent.
                result.put("requisitionId", state[i] == null ? null
                        : session.getFactory().getPersistenceUnitUtil().getIdentifier(state[i]));
            } else {
                result.put(names[i], scalar(state[i], persister, names[i]));
            }
        }
        if (result.containsKey("receiptQuantity")) {
            result.put("availableQuantity", available(result, "receiptQuantity", "usedQuantity"));
            result.put("physicalQuantity", physical(result, "receiptQuantity", "usedQuantity"));
        } else if (result.containsKey("producedQuantity")) {
            result.put("availableQuantity", available(result, "producedQuantity", "shippedQuantity"));
            result.put("physicalQuantity", physical(result, "producedQuantity", "shippedQuantity"));
        }
        return result;
    }

    private static Object scalar(Object value, EntityPersister persister, String property) {
        if (value == null || value instanceof Boolean || value instanceof Integer || value instanceof Long) return value;
        if (value instanceof BigDecimal decimal) {
            try {
                Column column = persister.getMappedClass().getDeclaredField(property).getAnnotation(Column.class);
                // PostgreSQL NUMERIC rounds to the mapped scale; do not report a precision it cannot store.
                if (column != null && column.precision() > 0) decimal = decimal.setScale(column.scale(), RoundingMode.HALF_UP);
                return decimal.stripTrailingZeros();
            } catch (NoSuchFieldException exception) {
                throw new HibernateException("Inventory audit numeric mapping unavailable: " + property, exception);
            }
        }
        if (value instanceof Enum<?> enumeration) return enumeration.name();
        if (value instanceof TemporalAccessor) return value.toString();
        if (value instanceof String string) return RequestAuditSupport.sanitizeText(string, 256);
        throw new HibernateException("Unsupported inventory audit scalar: " + value.getClass().getSimpleName());
    }

    private static BigDecimal available(Map<String, Object> snapshot, String received, String used) {
        BigDecimal quantity = physical(snapshot, received, used);
        if (quantity == null) return null;
        BigDecimal reserved = snapshot.get("reservedQuantity") instanceof BigDecimal value ? value : BigDecimal.ZERO;
        return quantity.subtract(reserved).stripTrailingZeros();
    }

    private static BigDecimal physical(Map<String, Object> snapshot, String received, String used) {
        if (!(snapshot.get(received) instanceof BigDecimal quantity)) return null;
        BigDecimal consumed = snapshot.get(used) instanceof BigDecimal value ? value : BigDecimal.ZERO;
        return quantity.subtract(consumed).stripTrailingZeros();
    }

    private static Set<String> factories(Connection connection, String type, Map<String, Object> value) throws SQLException {
        if (value == null) return Set.of();
        Set<String> factories = new LinkedHashSet<>();
        if ("InternalTransferItem".equals(type) || "FactoryMaterialRequisitionItem".equals(type)) {
            boolean transfer = "InternalTransferItem".equals(type);
            String sql = transfer
                    ? "select source_factory_id, target_factory_id from internal_transfers where id = ?"
                    : "select factory_id from factory_material_requisitions where id = ?";
            try (var statement = connection.prepareStatement(sql)) {
                statement.setString(1, Objects.toString(value.get(transfer ? "transferId" : "requisitionId"), null));
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) throw new SQLException("Inventory audit parent not found");
                    addFactory(factories, rows.getString(1));
                    if (transfer) addFactory(factories, rows.getString(2));
                }
            }
        } else if ("InternalTransfer".equals(type)) {
            addFactory(factories, value.get("sourceFactoryId"));
            addFactory(factories, value.get("targetFactoryId"));
        } else {
            addFactory(factories, value.get("factoryId"));
        }
        return factories;
    }

    private static void addFactory(Set<String> factories, Object value) throws SQLException {
        if (!(value instanceof String factory) || !factory.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new SQLException("Inventory audit has invalid entity factory");
        }
        factories.add(factory);
    }

    private static List<Map<String, Object>> diff(Map<String, Object> before, Map<String, Object> after) {
        Set<String> fields = new TreeSet<>();
        if (before != null) fields.addAll(before.keySet());
        if (after != null) fields.addAll(after.keySet());
        List<Map<String, Object>> differences = new ArrayList<>();
        for (String field : fields) {
            Object from = before == null ? null : before.get(field);
            Object to = after == null ? null : after.get(field);
            if (Objects.equals(from, to)) continue;
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("field", field); change.put("from", from); change.put("to", to);
            differences.add(change);
        }
        return differences;
    }

    private static void insert(Connection connection, String factory, String type, String id, String action,
                               Map<String, Object> before, Map<String, Object> after, Actor actor) throws SQLException {
        String sql = """
                insert into operation_logs
                (factory_id,user_id,username,module,action,entity_type,entity_id,old_value,new_value,diff,
                 summary,ip_address,user_agent,success,created_at,updated_at)
                values (?,?,?,'INVENTORY',?,?,?,cast(? as jsonb),cast(? as jsonb),cast(? as jsonb),?,?,?,true,?,?)
                """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, factory);
            if (actor.userId() == null) statement.setNull(2, Types.BIGINT); else statement.setLong(2, actor.userId());
            statement.setString(3, actor.username());
            statement.setString(4, action);
            statement.setString(5, type);
            statement.setString(6, InventoryAuditIdentity.indexId(id));
            statement.setString(7, json(before));
            statement.setString(8, json(after));
            statement.setString(9, json(diff(before, after)));
            statement.setString(10, "库存事务 " + action + " " + type + " correlation=" + actor.correlation()
                    + " origin=" + actor.origin() + " actorFactory=" + actor.factory());
            statement.setString(11, actor.ip());
            statement.setString(12, actor.agent());
            LocalDateTime now = LocalDateTime.now();
            statement.setObject(13, now);
            statement.setObject(14, now);
            if (statement.executeUpdate() != 1) throw new SQLException("Inventory audit insert count mismatch");
        }
    }

    private static String json(Object value) throws SQLException {
        if (value == null) return null;
        try { return JSON.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new SQLException("Inventory audit serialization failed", exception); }
    }

    private record Actor(Long userId, String username, String factory, String correlation, String origin, String ip, String agent) {
        static Actor capture() {
            HttpServletRequest request = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                    ? attributes.getRequest() : null;
            String correlation = request == null ? null : RequestAuditSupport.sanitizeCorrelationId(
                    Objects.toString(request.getAttribute(CorrelationIdFilter.CORRELATION_ID_HEADER), null));
            boolean authenticated = RequestAuditSupport.authenticated(request);
            return new Actor(authenticated ? RequestAuditSupport.userId(request) : null,
                    authenticated ? RequestAuditSupport.sanitizeText(RequestAuditSupport.username(request), 100) : null,
                    authenticated ? RequestAuditSupport.trustedFactoryId(request) : "unavailable",
                    correlation == null ? "unavailable" : correlation,
                    request == null ? "system" : authenticated ? "authenticated_request" : "unattributed_request",
                    request == null ? null : RequestAuditSupport.sanitizeText(request.getRemoteAddr(), 64),
                    request == null ? null : RequestAuditSupport.sanitizeText(request.getHeader("User-Agent"), 500));
        }
    }
}
