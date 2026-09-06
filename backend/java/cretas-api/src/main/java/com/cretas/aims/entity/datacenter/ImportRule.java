package com.cretas.aims.entity.datacenter;

import com.cretas.aims.entity.BaseEntity;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import java.util.List;
import java.util.Map;

/**
 * 导入规则中心 — 用户可配置的跨模块 Excel 导入规则.
 *
 * <p>Sprint 4 Chat K C-IMPORT-CENTER-1. ImportService 解析 Excel + 行级 validate +
 * dryrun 返回 row-level errors + commit 写 target_entity. 与 module-specific
 * /import 端点 (CustomerController:419/466, EquipmentController:453, ...) 并存.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "import_rules",
        indexes = {
                @Index(name = "idx_import_rules_factory_module", columnList = "factory_id,module_code"),
                @Index(name = "idx_import_rules_factory_created", columnList = "factory_id,created_at")
        })
@SQLDelete(sql = "UPDATE import_rules SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
public class ImportRule extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    @Column(name = "module_code", nullable = false, length = 64)
    private String moduleCode;

    @Column(name = "rule_name", nullable = false, length = 200)
    private String ruleName;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * Column mapping: {@code [{"excelCol":"客户名","entityField":"customerName","validator":"required|maxLength:100"},...]}.
     * Validator pipe-separated: {@code required|maxLength:N|minLength:N|numeric|email|regex:<pat>|enum:<csv>}.
     */
    @Type(JsonBinaryType.class)
    @Column(name = "mapping", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> mapping;

    /**
     * SKIP | UPDATE | ERROR — 如何处理 dedup_key_field 已存在的行.
     *
     * <p>⛔ 不给字段初始化: {@link com.cretas.aims.service.datacenter.impl.ImportServiceImpl#updateRule}
     * 把这个实体本身当 PATCH 载体用 ({@code patch.getDedupStrategy() != null} 才覆盖) ——
     * 字段初始化会让 {@code new ImportRule()} 永远非 null, PATCH 语义直接失效
     * (与 ApprovalWorkflow.nodesJson 同型, 2026-09-02 实测发现)。防 23502 改用
     * {@link #applyDefaults()}, 只在真正 INSERT 时补。
     */
    @Column(name = "dedup_strategy", nullable = false, length = 20)
    private String dedupStrategy;

    @Column(name = "dedup_key_field", length = 100)
    private String dedupKeyField;

    /** Fully-qualified class name, e.g. {@code com.cretas.aims.entity.Customer}. */
    @Column(name = "target_entity", nullable = false, length = 200)
    private String targetEntity;

    @Column(name = "created_by")
    private Long createdBy;

    /** 只在真正建行 (INSERT) 时补默认值, PATCH 载体角色的实例永不触发。 */
    @PrePersist
    void applyDefaults() {
        if (dedupStrategy == null) {
            dedupStrategy = "ERROR";
        }
    }
}
