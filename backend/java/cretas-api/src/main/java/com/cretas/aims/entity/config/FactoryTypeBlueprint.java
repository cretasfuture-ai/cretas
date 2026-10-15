package com.cretas.aims.entity.config;

import com.cretas.aims.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.Where;

/**
 * 工厂类型蓝图实体
 * 用于定义不同类型工厂的标准配置模板
 */
@Entity
@Table(name = "factory_type_blueprints")
@Data
@EqualsAndHashCode(callSuper = true)
@Where(clause = "deleted_at IS NULL")
public class FactoryTypeBlueprint extends BaseEntity {

    @Id
    @Column(length = 36)
    private String id;

    /** Null for the historical reusable industry templates; set for canonical factory blueprints. */
    @Column(name = "factory_id", length = 50)
    private String factoryId;

    @Column(name = "factory_name", length = 100)
    private String factoryName;

    @Column(name = "scope_type", nullable = false, length = 20)
    private String scopeType = "TEMPLATE";

    @Column(name = "schema_version", nullable = false, length = 50)
    private String schemaVersion = "factory-template/v1";

    @Column(name = "lifecycle_state", nullable = false, length = 20)
    private String lifecycleState = "PUBLISHED";

    @Column(name = "is_current", nullable = false)
    private Boolean isCurrent = false;

    /** Exact, user-reviewed onboarding request that produced this version. */
    @Column(name = "source_snapshot", columnDefinition = "JSONB")
    private String sourceSnapshot;

    /** Server-derived capability graph. It is never accepted from the browser as authority. */
    @Column(name = "capability_snapshot", columnDefinition = "JSONB")
    private String capabilitySnapshot;

    /** Version of the immutable server catalog used to derive capabilitySnapshot. */
    @Column(name = "capability_catalog_version", length = 64)
    private String capabilityCatalogVersion;

    /** Stable SHA-256 of the exact ordered catalog definitions used for this blueprint. */
    @Column(name = "capability_catalog_digest", length = 64)
    private String capabilityCatalogDigest;

    @Column(name = "plan_snapshot", columnDefinition = "JSONB")
    private String planSnapshot;

    @Column(name = "validation_report", columnDefinition = "JSONB")
    private String validationReport;

    @Column(name = "simulation_report", columnDefinition = "JSONB")
    private String simulationReport;

    @Column(name = "draft_idempotency_key", length = 100)
    private String draftIdempotencyKey;

    @Column(name = "request_digest", length = 64)
    private String requestDigest;

    @Column(name = "blueprint_created_by")
    private Long blueprintCreatedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "blueprint_created_actor_type", length = 32)
    private com.cretas.aims.security.AuthenticatedActorType blueprintCreatedActorType;
    @Column(name = "blueprint_created_actor_id")
    private Long blueprintCreatedActorId;

    @Column(name = "blueprint_updated_by")
    private Long blueprintUpdatedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "blueprint_updated_actor_type", length = 32)
    private com.cretas.aims.security.AuthenticatedActorType blueprintUpdatedActorType;
    @Column(name = "blueprint_updated_actor_id")
    private Long blueprintUpdatedActorId;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "published_by")
    private Long publishedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "published_actor_type", length = 32)
    private com.cretas.aims.security.AuthenticatedActorType publishedActorType;
    @Column(name = "published_actor_id")
    private Long publishedActorId;

    public void setBlueprintCreatedActor(com.cretas.aims.security.ActorRef actor) {
        blueprintCreatedActorType = actor == null ? null : actor.type();
        blueprintCreatedActorId = actor == null ? null : actor.id();
        blueprintCreatedBy = actor == null ? null : actor.factoryUserId();
    }
    public void setBlueprintUpdatedActor(com.cretas.aims.security.ActorRef actor) {
        blueprintUpdatedActorType = actor == null ? null : actor.type();
        blueprintUpdatedActorId = actor == null ? null : actor.id();
        blueprintUpdatedBy = actor == null ? null : actor.factoryUserId();
    }
    public void setPublishedActor(com.cretas.aims.security.ActorRef actor) {
        publishedActorType = actor == null ? null : actor.type();
        publishedActorId = actor == null ? null : actor.id();
        publishedBy = actor == null ? null : actor.factoryUserId();
    }

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    /**
     * 蓝图名称，如"水产加工厂"、"肉类加工厂"
     */
    @Column(nullable = false, length = 100)
    private String name;

    /**
     * 蓝图描述
     */
    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * 行业类型
     */
    @Column(name = "industry_type", length = 50)
    private String industryType;

    /**
     * 默认配置JSON (存储为字符串)
     * 包含：dailyCapacity, shiftCount, qualityStandards, temperatureControl等
     */
    @Column(name = "default_config", columnDefinition = "jsonb")
    private String defaultConfig;

    /**
     * 表单模板配置 (存储为字符串)
     * 数组格式：[{name, type, fields}]
     */
    @Column(name = "form_templates", columnDefinition = "jsonb")
    private String formTemplates;

    /**
     * 规则模板配置 (存储为字符串)
     * 数组格式：[{name, type, threshold}]
     */
    @Column(name = "rule_templates", columnDefinition = "jsonb")
    private String ruleTemplates;

    /**
     * 产品类型模板 (存储为字符串)
     * 数组格式：[{name, category, processSteps}]
     */
    @Column(name = "product_type_templates", columnDefinition = "jsonb")
    private String productTypeTemplates;

    /**
     * 部门模板 (存储为字符串)
     * 数组格式：[{name, type}]
     */
    @Column(name = "department_templates", columnDefinition = "jsonb")
    private String departmentTemplates;

    /**
     * 是否激活
     */
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    /**
     * 版本号
     */
    @Column(nullable = false)
    private Integer version = 1;

    @PrePersist
    @Override
    protected void onCreate() {
        super.onCreate();  // 设置 BaseEntity 的 createdAt/updatedAt
        if (id == null) {
            id = java.util.UUID.randomUUID().toString();
        }
        if (isActive == null) {
            isActive = true;
        }
        if (version == null) {
            version = 1;
        }
        if (scopeType == null) {
            scopeType = factoryId == null ? "TEMPLATE" : "FACTORY";
        }
        if (schemaVersion == null) {
            schemaVersion = factoryId == null ? "factory-template/v1" : "factory-blueprint/v1";
        }
        if (lifecycleState == null) {
            lifecycleState = factoryId == null ? "PUBLISHED" : "DRAFT";
        }
        if (isCurrent == null) {
            isCurrent = factoryId != null;
        }
    }
}
