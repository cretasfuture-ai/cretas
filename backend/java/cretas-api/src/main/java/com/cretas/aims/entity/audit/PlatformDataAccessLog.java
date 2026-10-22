package com.cretas.aims.entity.audit;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One request made by platform staff against a specific factory's data.
 *
 * <p>Append-only: written by {@code PlatformDataAccessLogInterceptor}, read by the factory
 * through {@code GET /api/mobile/{factoryId}/platform-access-log}. Not a {@code BaseEntity}
 * on purpose — an access record is never updated or soft-deleted, so it has no
 * {@code updated_at}/{@code deleted_at}. Schema: {@code V20261103_74__platform_data_access_log.sql}.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "platform_data_access_log",
        indexes = @Index(name = "idx_platform_data_access_log_factory_created",
                columnList = "target_factory_id,created_at"))
public class PlatformDataAccessLog {

    /** Where the target factory id was read from. */
    public enum TargetSource { PATH, QUERY }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "target_factory_id", nullable = false, length = 64)
    private String targetFactoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_source", nullable = false, length = 16)
    private TargetSource targetSource;

    /** {@code PLATFORM_ADMIN} or {@code FACTORY_USER} (legacy PLATFORM-factory / platform-role factory tokens). */
    @Column(name = "actor_type", nullable = false, length = 24)
    private String actorType;

    @Column(name = "actor_user_id", nullable = false)
    private Long actorUserId;

    @Column(name = "actor_username", length = 100)
    private String actorUsername;

    @Column(name = "actor_role", length = 64)
    private String actorRole;

    @Column(name = "actor_factory_id", length = 64)
    private String actorFactoryId;

    @Column(name = "http_method", nullable = false, length = 10)
    private String httpMethod;

    @Column(name = "request_path", nullable = false, length = 512)
    private String requestPath;

    /** Comma-separated query parameter names; values are never stored. */
    @Column(name = "query_param_names", length = 512)
    private String queryParamNames;

    @Column(name = "response_status", nullable = false)
    private Integer responseStatus;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "remote_addr", length = 64)
    private String remoteAddr;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;
}
