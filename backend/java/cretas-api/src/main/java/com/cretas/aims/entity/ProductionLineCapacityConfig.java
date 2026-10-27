package com.cretas.aims.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * S4: 一条产线 (可再细到产品) 每小时能做多少, 单位由对话建厂或工厂配置设定。
 * productTypeId 为 null = 该线通用; 同线同产品的专用行优先于通用行。
 * 插单可行性只认与订单同单位的行, 不换算、不套默认值。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "production_line_capacity_configs")
@SQLDelete(sql = "UPDATE production_line_capacity_configs SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
public class ProductionLineCapacityConfig extends BaseEntity {

    /** 件 / 千克 / 批。 */
    public enum CapacityUnit { PIECE, KG, BATCH }

    /** 写入入口: 对话建厂 / 工厂配置页。 */
    public enum Source { CONVERSATIONAL_BUILD, FACTORY_CONFIG }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    @Column(name = "production_line_id", length = 36, nullable = false)
    private String productionLineId;

    @Column(name = "product_type_id", length = 100)
    private String productTypeId;

    @Column(name = "capacity_per_hour", precision = 14, scale = 3, nullable = false)
    private BigDecimal capacityPerHour;

    @Enumerated(EnumType.STRING)
    @Column(name = "capacity_unit", length = 16, nullable = false)
    private CapacityUnit capacityUnit;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 32, nullable = false)
    private Source source;

    /** 谁配的 (工厂用户 / 平台管理员); 数字 id 只在各自命名空间内唯一。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", length = 32, nullable = false)
    private AuthenticatedActorType actorType;

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    /** 只在工厂用户配置时有值 (平台 id 不进这一列)。 */
    @Column(name = "configured_by")
    private Long configuredBy;

    public void setActor(ActorRef actor) {
        java.util.Objects.requireNonNull(actor, "Actor identity is required");
        this.actorType = actor.type();
        this.actorId = actor.id();
        this.configuredBy = actor.factoryUserId();
    }

    @Column(name = "effective_from", nullable = false)
    private LocalDateTime effectiveFrom;
}
