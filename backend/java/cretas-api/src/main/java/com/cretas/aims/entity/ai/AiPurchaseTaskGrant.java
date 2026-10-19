package com.cretas.aims.entity.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A server-issued, one-shot authorisation for one purchase-order draft.
 *
 * <p>This is deliberately an independent record.  It is the durable binding
 * between the authenticated caller, the exact purchase input and the result;
 * it is not a role grant and it does not make an entry point available by
 * itself.</p>
 */
@Entity
@Table(name = "ai_purchase_task_grants", indexes = {
        @Index(name = "idx_ai_purchase_grant_scope", columnList = "factory_id,user_id"),
        @Index(name = "idx_ai_purchase_grant_expiry", columnList = "expires_at"),
        @Index(name = "idx_ai_purchase_grant_preview", columnList = "preview_token_id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiPurchaseTaskGrant {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "tool_name", nullable = false, length = 100)
    private String toolName;

    @Column(name = "descriptor_version", nullable = false, length = 64)
    private String descriptorVersion;

    @Column(name = "supplier_id", nullable = false, length = 191)
    private String supplierId;

    @Column(name = "material_type_id", nullable = false, length = 191)
    private String materialTypeId;

    @Column(name = "quantity_cap", nullable = false, precision = 15, scale = 4)
    private BigDecimal quantityCap;

    @Column(name = "quantity_unit", nullable = false, length = 20)
    private String quantityUnit;

    @Column(name = "unit_price", nullable = false, precision = 15, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "amount_cap", nullable = false, precision = 18, scale = 2)
    private BigDecimal amountCap;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "tax_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal taxRate;

    @Column(name = "input_digest", nullable = false, length = 128)
    private String inputDigest;

    /** Immutable canonical summary of every approved purchase DTO field. */
    @Column(name = "approved_input_snapshot", nullable = false, columnDefinition = "TEXT")
    private String approvedInputSnapshot;

    /** Optional server-side preview token primary key; never a client assertion. */
    @Column(name = "preview_token_id")
    private Long previewTokenId;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "revoked_by")
    private Long revokedBy;

    @Column(name = "revoke_reason", length = 500)
    private String revokeReason;

    /** Business binding version. It advances on revoke and consume. */
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 1L;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    @Column(name = "consumed_request_key", length = 128)
    private String consumedRequestKey;

    @Column(name = "consumed_input_digest", length = 128)
    private String consumedInputDigest;

    @Column(name = "result_purchase_order_id", length = 191)
    private String resultPurchaseOrderId;

    @Column(name = "result_quantity", precision = 15, scale = 4)
    private BigDecimal resultQuantity;

    @Column(name = "result_amount", precision = 18, scale = 2)
    private BigDecimal resultAmount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        if (version == null) version = 1L;
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }
}
