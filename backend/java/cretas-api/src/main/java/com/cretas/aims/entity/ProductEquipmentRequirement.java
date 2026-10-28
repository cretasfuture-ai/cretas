package com.cretas.aims.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

/**
 * S4: 做某个产品必须用到的一台设备 (factory_equipment.id), 由配置时显式指定。
 * 产品没有任何在用行 = 设备口径未配置, 插单判为未验证。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "product_equipment_requirements")
@SQLDelete(sql = "UPDATE product_equipment_requirements SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
public class ProductEquipmentRequirement extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    @Column(name = "product_type_id", length = 100, nullable = false)
    private String productTypeId;

    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 32, nullable = false)
    private ProductionLineCapacityConfig.Source source;

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
}
