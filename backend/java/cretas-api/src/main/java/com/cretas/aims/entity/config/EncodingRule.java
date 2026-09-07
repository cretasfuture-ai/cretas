package com.cretas.aims.entity.config;

import com.cretas.aims.entity.BaseEntity;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;

/**
 * 编码规则配置实体
 *
 * 用于配置各类业务单据的编号生成规则
 * 支持前缀、日期格式、序列号、重置周期等配置
 *
 * 示例规则:
 * - 原材料批次: MB-{FACTORY}-{YYYYMMDD}-{SEQ:4} → MB-F001-20251229-0001
 * - 加工批次: PB-{FACTORY}-{YYYYMMDD}-{SEQ:5} → PB-F001-20251229-00001
 * - 出货记录: SH-{FACTORY}-{YYYYMM}-{SEQ:6} → SH-F001-202512-000001
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-29
 */
@Entity
@Table(name = "encoding_rules",
       indexes = {
           @Index(name = "idx_encoding_rules_factory", columnList = "factory_id"),
           @Index(name = "idx_encoding_rules_entity", columnList = "factory_id, entity_type")
       })
// 🔴 软删批 3-B (2026-09-07)：补 @SQLDelete，并**摘掉实体级的唯一声明**。
// prod 上这张表的业务唯一键是 `… WHERE deleted_at IS NULL` 的**部分**索引（由 现存迁移 创建），
// 而 JPA 表达不了部分唯一 ⇒ 实体里写 @UniqueConstraint 只会在 H2 建出**全量**键，比 prod 严。
// 补 @SQLDelete 之后「软删一行再建同键」在 prod 合法、在 H2 撞键，症状是测试红而线上是对的
// （#701→#717 就是这么炸出 4 个 error 的）⇒ 唯一键以 prod 的部分索引为准。
// ⚠️ 代价：H2 里这张表没有唯一性保护；行为由 opt-in 的真 PG 契约测试守（见 java-gate-pr 那条车道）。
@SQLDelete(sql = "UPDATE encoding_rules SET deleted_at = NOW() WHERE id = ? AND opt_lock_version = ?")
@Where(clause = "deleted_at IS NULL")
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class EncodingRule extends BaseEntity {

    @Id
    @Column(name = "id", length = 50)
    private String id;

    /**
     * 工厂ID
     * 为 null 时表示系统默认规则
     */
    @Column(name = "factory_id", length = 50)
    private String factoryId;

    /**
     * 实体类型
     * 如: MATERIAL_BATCH, PROCESSING_BATCH, SHIPMENT, QUALITY_INSPECTION
     */
    @Column(name = "entity_type", length = 50, nullable = false)
    private String entityType;

    /**
     * 规则名称
     */
    @Column(name = "rule_name", length = 100, nullable = false)
    private String ruleName;

    /**
     * 规则描述
     */
    @Column(name = "rule_description", length = 500)
    private String ruleDescription;

    /**
     * 编码模板
     * 支持占位符: {PREFIX}, {FACTORY}, {YYYY}, {MM}, {DD}, {SEQ:N}
     * 例如: MB-{FACTORY}-{YYYYMMDD}-{SEQ:4}
     */
    @Column(name = "encoding_pattern", length = 200, nullable = false)
    private String encodingPattern;

    /**
     * 固定前缀
     * 如: MB, PB, SH, QI
     */
    @Column(name = "prefix", length = 20)
    private String prefix;

    /**
     * 日期格式
     * 如: YYYYMMDD, YYYYMM, YYYY
     */
    @Column(name = "date_format", length = 20)
    private String dateFormat;

    /**
     * 序列号长度
     * 如: 4 表示 0001-9999
     */
    @Column(name = "sequence_length")
    @Builder.Default
    private Integer sequenceLength = 4;

    /**
     * 序列号重置周期
     * DAILY - 每日重置
     * MONTHLY - 每月重置
     * YEARLY - 每年重置
     * NEVER - 不重置
     */
    @Column(name = "reset_cycle", length = 20)
    @Builder.Default
    private String resetCycle = "DAILY";

    /**
     * 当前序列号
     * 用于追踪下一个可用序号
     */
    @Column(name = "current_sequence")
    @Builder.Default
    private Long currentSequence = 0L;

    /**
     * 最后重置日期
     * 用于判断是否需要重置序列号
     */
    @Column(name = "last_reset_date", length = 20)
    private String lastResetDate;

    /**
     * 分隔符
     * 如: -, _, /
     */
    @Column(name = "separator", length = 5)
    @Builder.Default
    private String separator = "-";

    /**
     * 是否包含工厂代码
     */
    @Column(name = "include_factory_code")
    @Builder.Default
    private Boolean includeFactoryCode = true;

    /**
     * 是否启用
     */
    @Column(name = "enabled")
    @Builder.Default
    private Boolean enabled = true;

    /**
     * 业务版本号 (用于规则变更追踪, 手动 incrementVersion 递增).
     * 注意: 不是 JPA @Version optimistic lock — 后者使用 optLockVersion 字段.
     */
    @Column(name = "version")
    @Builder.Default
    private Integer version = 1;

    /**
     * 创建者用户ID
     */
    @Column(name = "created_by")
    private Long createdBy;

    /**
     * Canvas-P3 AUD-4 P1: JPA @Version 乐观锁 (Long).
     * 与业务 version (Integer 配置版本号) 互不干扰; Flyway V20260824_05 加列默认 0.
     * Phase B (PR #201) uses {@code getOptLockVersion()} as the canonical accessor.
     */
    @jakarta.persistence.Version
    @Column(name = "opt_lock_version", nullable = false)
    @Builder.Default
    private Long optLockVersion = 0L;

    /**
     * 获取下一个序列号并递增
     * @return 下一个序列号
     */
    public Long getNextSequence() {
        this.currentSequence = (this.currentSequence == null ? 0L : this.currentSequence) + 1;
        return this.currentSequence;
    }

    /**
     * 重置序列号
     */
    public void resetSequence() {
        this.currentSequence = 0L;
    }

    /**
     * 递增版本号
     */
    public void incrementVersion() {
        this.version = (this.version == null ? 0 : this.version) + 1;
    }
}
