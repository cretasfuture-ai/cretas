package com.cretas.aims.entity.shortage;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;

/**
 * 缺料实例的执行台账 (V20261103_124): 生成了哪张采购草稿、谁做了什么决定、为什么。
 *
 * <p>同一实例同一物料只能有一张 DONE 的采购草稿 (部分唯一索引); 那张草稿不再有效时置 SUPERSEDED 才能再生成。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "shortage_case_actions")
@SQLDelete(sql = "UPDATE shortage_case_actions SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
public class ShortageCaseAction extends BaseEntity {

    public enum ActionType { PURCHASE_DRAFT, DECISION }

    public enum Status { DONE, SUPERSEDED }

    public enum Decision { CANCEL, MANUAL, CLOSE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_id", length = 36, nullable = false)
    private String caseId;

    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", length = 32, nullable = false)
    private ActionType actionType;

    @Column(name = "material_type_id", length = 191)
    private String materialTypeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private Status status;

    /** 采购草稿 = 采购单 id。 */
    @Column(name = "result_ref", length = 191)
    private String resultRef;

    @Column(name = "quantity", precision = 20, scale = 6)
    private BigDecimal quantity;

    @Column(name = "unit", length = 32)
    private String unit;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", length = 16)
    private Decision decision;

    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    @Column(name = "actor_type", length = 32, nullable = false)
    private String actorType;

    @Column(name = "actor_id")
    private Long actorId;
}
