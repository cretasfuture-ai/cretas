package com.cretas.aims.entity.shortage;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

/**
 * 缺料实例的留痕: 建立、每次刷新 (由什么事件触发)、阶段变化。系统触发的 actorType = SYSTEM。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "shortage_case_events")
@SQLDelete(sql = "UPDATE shortage_case_events SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
public class ShortageCaseEvent extends BaseEntity {

    public enum EventType { CREATED, REFRESHED, STAGE_CHANGED, NOTE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_id", length = 36, nullable = false)
    private String caseId;

    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", length = 32, nullable = false)
    private EventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_stage", length = 32)
    private ShortageCase.Stage fromStage;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_stage", length = 32)
    private ShortageCase.Stage toStage;

    @Column(name = "trigger_reason", length = 64, nullable = false)
    private String triggerReason;

    @Column(name = "detail", columnDefinition = "text")
    private String detail;

    @Column(name = "actor_type", length = 32)
    private String actorType;

    @Column(name = "actor_id")
    private Long actorId;
}
