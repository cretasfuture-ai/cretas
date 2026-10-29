package com.cretas.aims.entity.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One value of one interview item for one factory, with where it came from. Rows are never
 * overwritten or deleted: a value that is replaced only changes status (see V20261103_115).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "factory_confirmed_fact")
public class FactoryConfirmedFact {
    public static final String CONFIRMED = "CONFIRMED";
    public static final String CONFLICT_PENDING = "CONFLICT_PENDING";
    /** Extracted from free text or carried from an earlier version: waits for the user, not a confirmed fact. */
    public static final String PENDING_CONFIRM = "PENDING_CONFIRM";
    public static final String SUPERSEDED = "SUPERSEDED";
    public static final String REJECTED = "REJECTED";

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 191)
    private String factoryId;

    @Column(name = "field_key", nullable = false, length = 128)
    private String fieldKey;

    @Column(name = "field_label", length = 255)
    private String fieldLabel;

    @Column(name = "value_text", nullable = false, columnDefinition = "TEXT")
    private String valueText;

    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "source_kind", nullable = false, length = 32)
    private String sourceKind;

    @Column(name = "source_ref", length = 255)
    private String sourceRef;

    @Column(name = "supersedes_id", length = 64)
    private String supersedesId;

    @Column(name = "decided_by", length = 128)
    private String decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
