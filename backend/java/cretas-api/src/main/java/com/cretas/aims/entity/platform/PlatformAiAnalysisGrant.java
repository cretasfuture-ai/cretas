package com.cretas.aims.entity.platform;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 平台 AI 分析入口的一条授权 (V20261105_91)。有行 = 已开通, 撤销 = 删掉这一行。
 * 只有 {@code PlatformAiAnalysisGrantService} 写。不继承 BaseEntity: 不软删, 历史在 {@link PlatformAiAnalysisGrantAudit}。
 *
 * <p>{@link Persistable#isNew()} 恒为 true: 主键是业务键, 默认的 save 会先查后 merge, 并发开通时后到的一方会
 * 悄悄改掉已有行的开通人和理由。这里让它只能 INSERT, 撞主键就失败。这张表没有 UPDATE 路径。
 */
@Entity
@Table(name = "platform_ai_analysis_grants")
@IdClass(PlatformAiAnalysisGrant.Key.class)
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformAiAnalysisGrant implements Persistable<PlatformAiAnalysisGrant.Key> {

    public static final String ROLE = "ROLE";
    public static final String ACCOUNT = "ACCOUNT";

    /** {@link #ROLE} / {@link #ACCOUNT}。 */
    @Id
    @Column(name = "grantee_type", nullable = false, length = 10)
    private String granteeType;

    /** ROLE: platform_admins.platform_role 的取值; ACCOUNT: platform_admins.id 的字符串。 */
    @Id
    @Column(name = "grantee_ref", nullable = false, length = 50)
    private String granteeRef;

    @Column(name = "granted_by", nullable = false)
    private Long grantedBy;

    @Column(name = "granted_at", nullable = false)
    private LocalDateTime grantedAt;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Override
    @Transient
    public Key getId() {
        return new Key(granteeType, granteeRef);
    }

    @Override
    @Transient
    public boolean isNew() {
        return true;
    }

    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    @Getter
    public static class Key implements Serializable {
        private String granteeType;
        private String granteeRef;
    }
}
