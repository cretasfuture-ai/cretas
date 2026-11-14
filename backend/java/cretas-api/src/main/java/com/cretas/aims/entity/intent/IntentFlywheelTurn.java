package com.cretas.aims.entity.intent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 意图飞轮: 线上 AI 对话每一轮一条 (Flyway V20261114_87; INTENT-DISTILL-20261111)。
 * 原文列 ({@link #userInput} / {@link #contextSummary}) 只在 {@link SecrecyMode#PLAIN} 时有值 (迁移里的
 * {@code ck_aift_no_raw_when_secret} 钉住); 训练导出只读 {@code *_export} 列。
 */
@Entity
@Table(name = "ai_intent_flywheel_turns",
        uniqueConstraints = @UniqueConstraint(name = "uk_aift_turn_id", columnNames = "turn_id"),
        indexes = {
                @Index(name = "idx_aift_factory_created", columnList = "factory_id, created_at"),
                @Index(name = "idx_aift_confirm_digest", columnList = "factory_id, confirm_digest"),
                @Index(name = "idx_aift_session", columnList = "factory_id, user_id, session_id, created_at")
        })
@Getter
@Setter
@NoArgsConstructor
public class IntentFlywheelTurn {

    public enum Entry { NORMAL, STREAM }

    /** TEXT = 用户说 / 打的话; BUTTON = 点了一个意图按钮 (请求自带 intentCode)。 */
    public enum InputKind { TEXT, BUTTON }

    public enum SecrecyMode { PLAIN, REDACTED, TEXT_DROPPED }

    /** 决策动作: 直接办 / 出按钮让选 / 追问 / 明确判无对应功能 / 判不了。 */
    public enum Action { DIRECT, SHOW, ASK, NONE, UNKNOWN }

    /** 用户反应 (由下一轮或确认入口回填); null = 还没判。规则见 {@code FlywheelReactionRules}。 */
    public enum Reaction {
        OPTION_SELECTED, OPTION_OUTSIDE_CANDIDATES, ANSWERED_CLARIFICATION, CHANGED_REQUEST,
        CONFIRMED, CONFIRM_SKIPPED, SAME_INTENT_FOLLOWUP, MOVED_ON, UNKNOWN
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "turn_id", nullable = false, length = 36)
    private String turnId;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "session_id", length = 100)
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry", nullable = false, length = 10)
    private Entry entry;

    @Enumerated(EnumType.STRING)
    @Column(name = "input_kind", nullable = false, length = 10)
    private InputKind inputKind;

    @Column(name = "catalog_version", nullable = false, length = 80)
    private String catalogVersion;

    @Column(name = "option_set_hash", length = 80)
    private String optionSetHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "secrecy_mode", nullable = false, length = 20)
    private SecrecyMode secrecyMode;

    @Column(name = "user_input", columnDefinition = "TEXT")
    private String userInput;

    @Column(name = "user_input_export", columnDefinition = "TEXT")
    private String userInputExport;

    @Column(name = "user_input_cipher", columnDefinition = "TEXT")
    private String userInputCipher;

    @Column(name = "context_summary", columnDefinition = "TEXT")
    private String contextSummary;

    @Column(name = "context_export", columnDefinition = "TEXT")
    private String contextExport;

    /** 脱敏统计 (各类替换 / 像称呼但名单里没有的次数), JSON; 没有替换 = null。 */
    @Column(name = "redaction_json", columnDefinition = "TEXT")
    private String redactionJson;

    @Column(name = "requested_intent_code", length = 100)
    private String requestedIntentCode;

    @Column(name = "decision_source", nullable = false, length = 40)
    private String decisionSource;

    @Column(name = "decision_path", columnDefinition = "TEXT")
    private String decisionPath;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_action", nullable = false, length = 20)
    private Action decisionAction;

    @Column(name = "decided_intent_code", length = 100)
    private String decidedIntentCode;

    @Column(name = "confidence", precision = 6, scale = 4)
    private BigDecimal confidence;

    @Column(name = "candidates_json", columnDefinition = "TEXT")
    private String candidatesJson;

    @Column(name = "final_status", nullable = false, length = 40)
    private String finalStatus;

    @Column(name = "response_intent_code", length = 100)
    private String responseIntentCode;

    @Column(name = "confirm_digest", length = 128)
    private String confirmDigest;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "reaction", length = 30)
    private Reaction reaction;

    @Column(name = "reaction_option_rank")
    private Integer reactionOptionRank;

    @Column(name = "reaction_turn_id", length = 36)
    private String reactionTurnId;

    @Column(name = "reacted_at")
    private LocalDateTime reactedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
