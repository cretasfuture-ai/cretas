package com.cretas.aims.entity.rules;

import com.cretas.aims.entity.BaseEntity;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.Where;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Canvas-Rules business rule definition.
 *
 * One row = one rule. The combination of (factoryId, ruleCode) is unique.
 * RuleEngine loads rules by (factoryId, scope, enabled=true) ORDER BY priority ASC.
 *
 * Spec: docs/superpowers/specs/2026-05-18-canvas-rules-phase4a-spec.md §3
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-18
 */
@Entity
// 🔴 实体不再声明这张表的业务唯一键 (2026-09-07, FIX-ENTITY-FULL-UNIQUE-VS-PROD-PARTIAL):
// prod 上是 uq_business_rules_factory_code_active —— `WHERE deleted_at IS NULL` 的**部分**唯一索引; 而 JPA 表达不了部分唯一,
// 实体里的 @UniqueConstraint 只能在 H2 建出**全量**键 ⇒ H2 比 prod 严。
// 补 @SQLDelete 之后「软删一行再建同键」在 prod 合法、在 H2 撞键, 表现为测试红而线上是对的
// (#701 合入后 logistics 三个测试类 4 个 error 就是这么来的, 全部只在 H2 复现)。
// ⇒ 唯一键以 prod 的部分索引为准, 实体侧不再重复声明。
// ⚠️ 代价: H2 里这张表**没有**唯一性保护 —— 与 #712 对 material_product_conversions 的处置一致。
@Table(name = "business_rules",
    indexes = {
        @Index(name = "idx_business_rules_factory_scope",
               columnList = "factory_id, scope, enabled, priority")
    })
@SQLDelete(sql = "UPDATE business_rules SET deleted_at = NOW() WHERE id = ? AND version = ?")
@Where(clause = "deleted_at IS NULL")
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class BusinessRule extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    /** Unique within (factoryId, ruleCode). Used as stable identifier in AI Tool params. */
    @Column(name = "rule_code", length = 100, nullable = false)
    private String ruleCode;

    @Column(name = "rule_name", length = 255)
    private String ruleName;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", length = 20, nullable = false)
    private RuleScope scope;

    /** SpEL expression. Evaluated against inputObject as root context. Empty → always true. */
    @Column(name = "condition_spel", columnDefinition = "TEXT")
    private String conditionSpel;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", length = 30, nullable = false)
    private RuleActionType actionType;

    /**
     * Action payload. Shape depends on actionType — see spec §2.2.
     *  - LOG: {"level":"INFO","message":"..."}
     *  - REJECT: {"reason":"...","actionHint":"..."}
     *  - MODIFY: {"field":"...","value":...} or {"field":"...","valueSpel":"..."}
     *  - TRIGGER_WORKFLOW: {"workflowCode":"...","ctx":{...}}
     */
    @Type(JsonBinaryType.class)
    @Column(name = "action_config_json", columnDefinition = "jsonb")
    @lombok.Builder.Default
    private Map<String, Object> actionConfigJson = new HashMap<>();

    /** Smaller = earlier. Default 100. REJECT short-circuits later rules. */
    @Column(name = "priority", nullable = false)
    @lombok.Builder.Default
    private Integer priority = 100;

    @Column(name = "enabled", nullable = false)
    @lombok.Builder.Default
    private Boolean enabled = true;

    /**
     * AUD-4 P1 (edge audit 2026-05-20): JPA optimistic locking version.
     *
     * <p>Hibernate manages this column automatically — first save inserts
     * with {@code version=0}, every subsequent {@code save()} or
     * {@code merge()} increments it. If two parallel PUT requests load
     * the same row (both see {@code version=N}), Hibernate's flush detects
     * the stale snapshot on the loser and throws
     * {@link org.springframework.orm.ObjectOptimisticLockingFailureException}.
     * {@link com.cretas.aims.exception.GlobalExceptionHandler} catches it
     * and surfaces a 409 with the actionHint
     * "请刷新页面查看最新数据后再编辑" — the user can retry without
     * silently losing the other user's edits (Lost Update prevention).
     *
     * <p>Column is added via Flyway {@code V20260626_02} with
     * {@code DEFAULT 0 NOT NULL}; backfill is implicit because the migration
     * runs before any code reads/writes the column.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
