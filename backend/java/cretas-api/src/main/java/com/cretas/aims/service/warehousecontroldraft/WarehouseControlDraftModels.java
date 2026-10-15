package com.cretas.aims.service.warehousecontroldraft;

import com.cretas.aims.entity.factory.FactoryWarehouse.WarehouseType;

import java.math.BigDecimal;
import java.util.List;

/** Closed typed records for {@link WarehouseControlDraftCommand}. */
public final class WarehouseControlDraftModels {

    public static final String SCHEMA_VERSION = "warehouse-control-draft/v1";
    public static final String DEPENDENCY_BASIS = "CAPABILITY_CONTRACT:factory";
    public static final String TEMPERATURE_UNIT = "Cel";
    public static final String DEVIATION_ALGORITHM =
            "OUTSIDE_INCLUSIVE_RANGE_CONTINUOUS_FOR_TOLERANCE_MINUTES";

    private WarehouseControlDraftModels() {
    }

    public enum DraftOperation {
        CREATE, UPDATE
    }

    /** Mirrors the existing ColdChainEquipment closed type vocabulary. */
    public enum ColdControlType {
        FREEZER, REFRIGERATOR, COLD_CHAIN_VEHICLE, COLD_DISPLAY, COLD_STORAGE
    }

    public record AuthenticatedOperator(com.cretas.aims.security.ActorRef actor, String factoryId, java.util.Set<String> permissions) {
        public AuthenticatedOperator(long operatorId, String factoryId, java.util.Set<String> permissions) {
            this(new com.cretas.aims.security.ActorRef(com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER, operatorId), factoryId, permissions);
        }
        public long operatorId() { return actor.id(); }
    }

    public record RequestedWarehouse(
            DraftOperation operation,
            String code,
            String name,
            WarehouseType type,
            Boolean active,
            String remarks,
            String changeReason) {
        public RequestedWarehouse {
            code = upper(code);
            name = canonicalText(name);
            remarks = canonicalText(remarks);
            changeReason = canonicalText(changeReason);
        }
    }

    public record RequestedColdControl(
            DraftOperation operation,
            String equipmentCode,
            String equipmentName,
            ColdControlType type,
            String warehouseCode,
            BigDecimal targetTempMinCelsius,
            BigDecimal targetTempMaxCelsius,
            Integer toleranceMinutes,
            String location,
            Boolean active,
            String controlBasis) {
        public RequestedColdControl {
            equipmentCode = upper(equipmentCode);
            equipmentName = canonicalText(equipmentName);
            warehouseCode = upper(warehouseCode);
            targetTempMinCelsius = canonicalDecimal(targetTempMinCelsius);
            targetTempMaxCelsius = canonicalDecimal(targetTempMaxCelsius);
            location = canonicalText(location);
            controlBasis = canonicalText(controlBasis);
        }
    }

    /** No raw JSON, SQL, expression, inventory quantity, batch or receipt fields are accepted. */
    public record DraftRequest(
            String factoryId,
            String blueprintId,
            long blueprintVersion,
            String sourceDigest,
            List<RequestedWarehouse> warehouses,
            List<RequestedColdControl> coldControls,
            String confirmedPreviewDigest,
            String idempotencyKey,
            long operatorId) {
        public DraftRequest {
            factoryId = clean(factoryId);
            blueprintId = clean(blueprintId);
            sourceDigest = clean(sourceDigest).toLowerCase();
            warehouses = warehouses == null ? List.of() : List.copyOf(warehouses);
            coldControls = coldControls == null ? List.of() : List.copyOf(coldControls);
            confirmedPreviewDigest = clean(confirmedPreviewDigest).toLowerCase();
            idempotencyKey = clean(idempotencyKey);
        }
    }

    public record ResolvedWarehouse(
            int ordinal,
            DraftOperation operation,
            String targetWarehouseId,
            String code,
            String name,
            WarehouseType type,
            boolean active,
            String remarks,
            String changeReason,
            String currentSnapshotDigest) {
    }

    public record ResolvedColdControl(
            int ordinal,
            DraftOperation operation,
            Long targetEquipmentId,
            String equipmentCode,
            String equipmentName,
            ColdControlType type,
            String warehouseCode,
            BigDecimal targetTempMinCelsius,
            BigDecimal targetTempMaxCelsius,
            int toleranceMinutes,
            String location,
            boolean active,
            String controlBasis,
            String currentSnapshotDigest,
            String temperatureUnit,
            String deviationAlgorithm) {
        public ResolvedColdControl {
            temperatureUnit = TEMPERATURE_UNIT;
            deviationAlgorithm = DEVIATION_ALGORITHM;
        }
    }

    public record DraftIssue(String code, String target, String message, String recovery) {
    }

    public record DraftPreview(
            String schemaVersion,
            String status,
            String factoryId,
            List<String> dependencies,
            boolean dependencyClosureComplete,
            String dependencyBasis,
            List<ResolvedWarehouse> warehouses,
            List<ResolvedColdControl> coldControls,
            int warehouseCount,
            int coldControlCount,
            String sourceSnapshotDigest,
            String desiredDigest,
            String previewDigest,
            List<DraftIssue> issues,
            int expectedBusinessWriteCount,
            int expectedPersistedRowCount,
            int inventoryQuantityWriteCount,
            int batchWriteCount,
            int receivingWriteCount,
            String publishState,
            String activationState) {
        public DraftPreview {
            schemaVersion = SCHEMA_VERSION;
            dependencies = List.of("factory");
            dependencyBasis = DEPENDENCY_BASIS;
            warehouses = List.copyOf(warehouses);
            coldControls = List.copyOf(coldControls);
            issues = List.copyOf(issues);
            expectedBusinessWriteCount = "READY".equals(status) ? 1 : 0;
            expectedPersistedRowCount = "READY".equals(status) ? 1 + warehouseCount + coldControlCount : 0;
            inventoryQuantityWriteCount = 0;
            batchWriteCount = 0;
            receivingWriteCount = 0;
            publishState = "NOT_PUBLISHED";
            activationState = "NOT_ACTIVATED";
        }
    }

    public record DraftReceipt(
            String schemaVersion,
            String receiptId,
            String factoryId,
            String draftId,
            String status,
            String sourceSnapshotDigest,
            String desiredDigest,
            String previewDigest,
            String requestDigest,
            int warehouseCount,
            int coldControlCount,
            boolean dependencyClosureComplete,
            long lockVersion,
            boolean replayed,
            int businessWriteCount,
            int originalBusinessWriteCount,
            int persistedRowCount,
            int inventoryQuantityWriteCount,
            int batchWriteCount,
            int receivingWriteCount,
            String publishState,
            String activationState,
            String recovery) {
        public DraftReceipt {
            schemaVersion = SCHEMA_VERSION;
            inventoryQuantityWriteCount = 0;
            batchWriteCount = 0;
            receivingWriteCount = 0;
            publishState = "NOT_PUBLISHED";
            activationState = "NOT_ACTIVATED";
        }
    }

    public record DraftReadback(
            String schemaVersion,
            String factoryId,
            String draftId,
            String status,
            List<ResolvedWarehouse> warehouses,
            List<ResolvedColdControl> coldControls,
            int inventoryQuantityWriteCount,
            int batchWriteCount,
            int receivingWriteCount,
            String publishState,
            String activationState) {
        public DraftReadback {
            schemaVersion = SCHEMA_VERSION;
            warehouses = List.copyOf(warehouses);
            coldControls = List.copyOf(coldControls);
            inventoryQuantityWriteCount = 0;
            batchWriteCount = 0;
            receivingWriteCount = 0;
            publishState = "NOT_PUBLISHED";
            activationState = "NOT_ACTIVATED";
        }
    }

    public record RecoveryPlan(
            String schemaVersion,
            String factoryId,
            String draftId,
            String expectedDesiredDigest,
            String action,
            String effect,
            String publishState,
            String activationState) {
        public RecoveryPlan {
            schemaVersion = SCHEMA_VERSION;
            publishState = "NOT_PUBLISHED";
            activationState = "NOT_ACTIVATED";
        }
    }

    static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String upper(String value) {
        return clean(value).toUpperCase(java.util.Locale.ROOT);
    }

    private static String canonicalText(String value) {
        return clean(value).replaceAll("\\s+", " ");
    }

    private static BigDecimal canonicalDecimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros();
    }
}
