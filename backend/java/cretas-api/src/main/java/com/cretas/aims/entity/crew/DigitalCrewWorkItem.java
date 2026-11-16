package com.cretas.aims.entity.crew;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 数字员工办的一件事 (V20261115_61): 等我确认 + 额度四态 (R29)。
 *
 * <p>DELEGATED 已交办 (等有权限的人确认, 不扣) → 确认人按自己身份预览 → 确认 → CONFIRMING (在途) →
 * DONE 已办成 (回读预览凭据为 CONFIRMED, 扣 1) / OUTCOME_UNKNOWN 结果不明 (不扣);
 * 有人「不办」→ NOT_DONE 没办成 (不扣)。确定没写入的失败 (凭据被拒 / 改前值已变) 回到 DELEGATED, 写明原因。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "digital_crew_work_items")
public class DigitalCrewWorkItem {

    public enum Status {
        DELEGATED("已交办"), CONFIRMING("办理中"), DONE("已办成"), NOT_DONE("没办成"), OUTCOME_UNKNOWN("结果不明");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    @Column(name = "member_key", length = 20, nullable = false)
    private String memberKey;

    @Column(name = "intent_code", length = 100, nullable = false)
    private String intentCode;

    @Column(name = "intent_name", length = 200)
    private String intentName;

    @Column(name = "title", length = 200, nullable = false)
    private String title;

    @Column(name = "user_input", length = 500, nullable = false)
    private String userInput;

    /** 意图配置声明的写权限码 (如 production:read_write); 确认人必须持有。 */
    @Column(name = "required_permission", length = 100, nullable = false)
    private String requiredPermission;

    @Column(name = "module", length = 50, nullable = false)
    private String module;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private Status status;

    @Column(name = "quota_cost", nullable = false)
    private int quotaCost;

    /** 交办时的预览摘要 (给人看的文字, 不是执行依据; 执行前确认人会重新预览)。 */
    @Column(name = "preview_summary", columnDefinition = "TEXT")
    private String previewSummary;

    @Column(name = "created_by_user_id", nullable = false)
    private Long createdByUserId;

    @Column(name = "created_by_name", length = 100, nullable = false)
    private String createdByName;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "previewed_by_user_id")
    private Long previewedByUserId;

    @Column(name = "previewed_at")
    private LocalDateTime previewedAt;

    @Column(name = "command_digest", length = 64)
    private String commandDigest;

    /**
     * 预览凭据那一行的 id (不是凭据本身, 不能拿来确认)。对账用: 凭据被别的入口用掉、或确认在途卡住时,
     * 按 (id, 工厂, 预览人) 回读凭据状态定四态。
     */
    @Column(name = "preview_token_id")
    private Long previewTokenId;

    @Column(name = "confirmed_by_user_id")
    private Long confirmedByUserId;

    @Column(name = "confirmed_by_name", length = 100)
    private String confirmedByName;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    /** 北京时间真实日期, 额度按它计数。 */
    @Column(name = "resolved_date")
    private LocalDate resolvedDate;

    @Column(name = "result_status", length = 40)
    private String resultStatus;

    @Column(name = "result_note", length = 500)
    private String resultNote;

    /** 回读依据 (凭据状态 + 业务对象), 给人看。 */
    @Column(name = "evidence", length = 500)
    private String evidence;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
