package com.cretas.aims.entity.blueprintapproval;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.security.AuthenticatedActorType;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Where;
import org.hibernate.type.SqlTypes;

/** Only server-generated plans are stored; HTTP bodies never supply either JSON field. */
@Entity
@Table(name = "factory_assembly_approvals")
@Where(clause = "deleted_at IS NULL")
@Getter @Setter
public class FactoryAssemblyApproval extends BaseEntity {
    @Id @Column(length = 36, nullable = false, updatable = false) private String id;
    @Column(name = "factory_id", length = 50, nullable = false, updatable = false) private String factoryId;
    @Column(name = "blueprint_id", length = 100, nullable = false, updatable = false) private String blueprintId;
    @Column(name = "capability_id", length = 40, nullable = false, updatable = false) private String capabilityId;
    @Column(name = "draft_id", length = 120, nullable = false, updatable = false) private String draftId;
    @Column(name = "plan_digest", length = 64, nullable = false, updatable = false)
    @JdbcTypeCode(SqlTypes.CHAR) private String planDigest;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "plan_request_json", columnDefinition = "jsonb", nullable = false, updatable = false)
    private String planRequestJson;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "plan_json", columnDefinition = "jsonb", nullable = false, updatable = false)
    private String planJson;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "approved_json", columnDefinition = "jsonb") private String approvedJson;
    @Enumerated(EnumType.STRING)
    @Column(name = "requested_actor_type", length = 32, nullable = false, updatable = false)
    private AuthenticatedActorType requestedActorType;
    @Column(name = "requested_actor_id", nullable = false, updatable = false) private Long requestedActorId;
    @Enumerated(EnumType.STRING)
    @Column(name = "approved_actor_type", length = 32) private AuthenticatedActorType approvedActorType;
    @Column(name = "approved_actor_id") private Long approvedActorId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "approval_digest", length = 64) private String approvalDigest;
    @Column(name = "approved_at") private Instant approvedAt;
    @Column(name = "expires_at") private Instant expiresAt;
    @Column(name = "status", length = 20, nullable = false) private String status;
    @Version @Column(name = "lock_version", nullable = false) private Long lockVersion;
}
