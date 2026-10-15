package com.cretas.aims.entity.supplier;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 工厂级供应商准入/验收规则配置 —— 一个工厂一行。
 *
 * <h2>为什么存在</h2>
 *
 * 在此之前 {@code SupplierAdmissionRuleServiceImpl} 把整份配置放在
 * {@code private final Map<String, SupplierRuleConfig> ruleConfigCache = new ConcurrentHashMap<>()}
 * 里, {@code updateRuleConfiguration} 全程不落库。后果是三层的:
 * <b>重启即丢</b>、<b>蓝绿部署两侧各存各的</b>、<b>多实例互相看不见对方的改动</b>。
 * 管理员改完门槛看到「保存成功」, 下一次发布就回到默认值, 而且没有任何痕迹。
 *
 * <h2>为什么两组规则用 jsonb 而不是逐列拆开</h2>
 *
 * 逐列拆 13 个标量意味着实体↔DTO 两个方向各写一遍手工映射 ——
 * 而「一个字段穿三层, 每层各自静默丢掉它, 每层都返回成功」是本仓反复踩过的形状。
 * 整存整取只有一处转换, 并由 round-trip 测试逐字段守住。
 * 与 {@code business_rules.action_config_json} 同风格。
 *
 * <h2>0 行的语义</h2>
 *
 * <b>0 行 = 该厂沿用代码里的默认门槛, 不是缺口。</b>
 * {@code getRuleConfiguration} 读不到行时返回内存默认值且<b>不落库</b> ——
 * 读路径不产生写副作用。只有管理员真正改过, 才会出现一行。
 *
 * @since 2026-08-14
 */
@Entity
@Table(name = "supplier_admission_rule_configs",
        uniqueConstraints = @UniqueConstraint(name = "uk_sarc_factory", columnNames = "factory_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SupplierAdmissionRuleConfig {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    /** 配置版本号, 每次保存自增。默认配置(未落库)对外报 1, 首次保存后为 2。 */
    @Column(name = "config_version", nullable = false)
    @Builder.Default
    private Integer configVersion = 1;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;

    /**
     * {@code AdmissionRules} 的整体快照:
     * requireBusinessLicense / requireQualityCertificates / minRating /
     * requireCreditLimit / minHistoricalPassRate / minSupplyCount。
     */
    @Type(JsonType.class)
    @Column(name = "admission_rules", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private Map<String, Object> admissionRules = new HashMap<>();

    /**
     * {@code AcceptanceRules} 的整体快照:
     * newSupplierLevel / relaxedThreshold / strictThreshold /
     * defaultSamplePercentage / highRiskSamplePercentage / minSampleSize / maxSampleSize。
     */
    @Type(JsonType.class)
    @Column(name = "acceptance_rules", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private Map<String, Object> acceptanceRules = new HashMap<>();

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
