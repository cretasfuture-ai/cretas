package com.cretas.aims.service.formschemadraft;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Closed, wire-neutral contract for dynamic form schema DRAFTs.
 *
 * <p>The immutable capability catalog v2 declares {@code forms -> modules}. Every form binds to a
 * module that a same-lineage, readback-ready module-config DRAFT actually enables.</p>
 *
 * <p>This contract deliberately does NOT reuse the legacy {@code FormTemplate} model, whose
 * {@code schema_json} / {@code ui_schema_json} columns are free TEXT and whose service writes
 * formal templates directly. Fields here are typed rows with per-type constraints; there is no
 * JSON schema, no UI schema blob, no expression and no formal template write.</p>
 */
public final class FormSchemaDraftModels {
    public static final String SCHEMA_VERSION = "form-schema-draft/v1";
    public static final String ALGORITHM_VERSION = "form-schema-policy/v1";
    public static final String DEPENDENCY_BASIS = "CAPABILITY_CATALOG:modules";

    private FormSchemaDraftModels() {
    }

    /** Server-derived retention and audit posture; the request only picks the class. */
    public enum RetentionClass {
        FOOD_SAFETY_CRITICAL, QUALITY_RECORD, OPERATIONAL_RECORD, TRANSIENT_WORKSHEET
    }

    public enum FieldType {
        TEXT, INTEGER, DECIMAL, QUANTITY, BOOLEAN, DATE, DATETIME,
        SINGLE_SELECT, MULTI_SELECT, PHOTO, SIGNATURE
    }

    public enum ResponsibleRole {
        QUALITY_INSPECTOR, PRODUCTION_SUPERVISOR, WAREHOUSE_SUPERVISOR, SANITATION_OPERATOR
    }

    public static int retentionDays(RetentionClass retentionClass) {
        return switch (retentionClass) {
            case FOOD_SAFETY_CRITICAL -> 730;
            case QUALITY_RECORD -> 365;
            case OPERATIONAL_RECORD -> 180;
            case TRANSIENT_WORKSHEET -> 30;
        };
    }

    public static boolean auditTrailRequired(RetentionClass retentionClass) {
        return retentionClass != RetentionClass.TRANSIENT_WORKSHEET;
    }

    public static boolean immutableAfterSubmit(RetentionClass retentionClass) {
        return retentionClass == RetentionClass.FOOD_SAFETY_CRITICAL
                || retentionClass == RetentionClass.QUALITY_RECORD;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ModuleConfigPin(String draftId, String desiredDigest) {
        public ModuleConfigPin {
            draftId = clean(draftId);
            desiredDigest = clean(desiredDigest).toLowerCase(Locale.ROOT);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record FieldInput(
            String fieldKey,
            String label,
            FieldType fieldType,
            boolean required,
            Integer maxLength,
            BigDecimal minValue,
            BigDecimal maxValue,
            Integer decimalScale,
            String unitCode,
            List<String> optionCodes,
            String helpText) {
        public FieldInput {
            fieldKey = clean(fieldKey).toLowerCase(Locale.ROOT);
            label = clean(label);
            unitCode = clean(unitCode);
            optionCodes = optionCodes == null ? List.of()
                    : optionCodes.stream().map(value -> clean(value).toUpperCase(Locale.ROOT))
                            .toList();
            helpText = clean(helpText);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record FormInput(
            String formCode,
            String title,
            String moduleId,
            RetentionClass retentionClass,
            ResponsibleRole responsibleRole,
            List<FieldInput> fields,
            String changeReason) {
        public FormInput {
            formCode = clean(formCode).toUpperCase(Locale.ROOT);
            title = clean(title);
            moduleId = clean(moduleId).toLowerCase(Locale.ROOT);
            fields = fields == null ? List.of() : List.copyOf(fields);
            changeReason = clean(changeReason);
        }
    }

    public record DerivedField(
            int ordinal,
            String fieldKey,
            String label,
            FieldType fieldType,
            boolean required,
            Integer maxLength,
            BigDecimal minValue,
            BigDecimal maxValue,
            Integer decimalScale,
            String unitCode,
            List<String> optionCodes,
            String helpText) {
        public DerivedField {
            optionCodes = List.copyOf(optionCodes);
        }
    }

    public record DerivedForm(
            int ordinal,
            String formCode,
            String title,
            String moduleId,
            String moduleLabel,
            String moduleSnapshotDigest,
            RetentionClass retentionClass,
            int retentionDays,
            boolean auditTrailRequired,
            boolean immutableAfterSubmit,
            ResponsibleRole responsibleRole,
            String fieldIdentityDigest,
            List<DerivedField> fields,
            String changeReason) {
        public DerivedForm {
            fields = List.copyOf(fields);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DraftRequest(
            String factoryId,
            String blueprintId,
            long blueprintVersion,
            String sourceDigest,
            ModuleConfigPin moduleConfig,
            List<FormInput> forms,
            String confirmedPreviewDigest,
            String idempotencyKey,
            long operatorId) {
        public DraftRequest {
            factoryId = clean(factoryId);
            blueprintId = clean(blueprintId);
            sourceDigest = clean(sourceDigest).toLowerCase(Locale.ROOT);
            forms = forms == null ? List.of() : List.copyOf(forms);
            confirmedPreviewDigest = clean(confirmedPreviewDigest).toLowerCase(Locale.ROOT);
            idempotencyKey = clean(idempotencyKey);
        }
    }

    public record AuthenticatedOperator(com.cretas.aims.security.ActorRef actor, String factoryId, Set<String> permissions) {
        public AuthenticatedOperator {
            factoryId = clean(factoryId);
            permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        }

        public AuthenticatedOperator(long operatorId, String factoryId, Set<String> permissions) {
            this(new com.cretas.aims.security.ActorRef(
                    com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER, operatorId),
                    factoryId, permissions);
        }

        /** Legacy accessor retained for request compatibility; identity is actor-typed. */
        public long operatorId() {
            return actor.id();
        }
    }

    public record DraftIssue(String code, String target, String message, String recovery) {
    }

    public record DraftPreview(
            String schemaVersion,
            String algorithmVersion,
            String status,
            String factoryId,
            List<String> dependencies,
            DraftRequest request,
            List<DerivedForm> forms,
            String sourceSnapshotDigest,
            String moduleSnapshotDigest,
            String authoritySnapshotDigest,
            String desiredDigest,
            String previewDigest,
            List<DraftIssue> issues,
            int expectedBusinessWriteCount,
            int expectedPersistedRowCount,
            int formTemplateWriteCount,
            int formTemplateVersionWriteCount,
            int formSubmissionWriteCount,
            int recordWriteCount,
            String publishState,
            String activationState) {
        public DraftPreview {
            schemaVersion = SCHEMA_VERSION;
            algorithmVersion = ALGORITHM_VERSION;
            dependencies = List.of("modules");
            forms = List.copyOf(forms);
            issues = List.copyOf(issues);
            expectedBusinessWriteCount = "READY".equals(status) ? 1 : 0;
            expectedPersistedRowCount = "READY".equals(status)
                    ? 2 + forms.size() + forms.stream().mapToInt(form -> form.fields().size()).sum()
                    : 0;
            formTemplateWriteCount = 0;
            formTemplateVersionWriteCount = 0;
            formSubmissionWriteCount = 0;
            recordWriteCount = 0;
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
            int formCount,
            int fieldCount,
            int evidenceCount,
            long lockVersion,
            boolean replayed,
            int businessWriteCount,
            int originalBusinessWriteCount,
            int persistedRowCount,
            int formTemplateWriteCount,
            int formTemplateVersionWriteCount,
            int formSubmissionWriteCount,
            int recordWriteCount,
            String publishState,
            String activationState,
            String recovery) {
        public DraftReceipt {
            schemaVersion = SCHEMA_VERSION;
            formTemplateWriteCount = 0;
            formTemplateVersionWriteCount = 0;
            formSubmissionWriteCount = 0;
            recordWriteCount = 0;
            publishState = "NOT_PUBLISHED";
            activationState = "NOT_ACTIVATED";
        }
    }

    public record DraftReadback(
            String schemaVersion,
            String factoryId,
            String draftId,
            String status,
            List<DerivedForm> forms,
            String sourceSnapshotDigest,
            String desiredDigest,
            int formTemplateWriteCount,
            int formTemplateVersionWriteCount,
            int formSubmissionWriteCount,
            int recordWriteCount,
            String publishState,
            String activationState) {
        public DraftReadback {
            schemaVersion = SCHEMA_VERSION;
            forms = List.copyOf(forms);
            formTemplateWriteCount = 0;
            formTemplateVersionWriteCount = 0;
            formSubmissionWriteCount = 0;
            recordWriteCount = 0;
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
}
