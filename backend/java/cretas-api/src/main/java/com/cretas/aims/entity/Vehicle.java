package com.cretas.aims.entity;

import lombok.Data;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 车辆实体
 * 用于仓库装车管理
 */
@Data
@Entity
@Table(name = "vehicles")
public class Vehicle {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "factory_id", length = 36, nullable = false)
    private String factoryId;

    /**
     * 车牌号
     */
    @Column(name = "plate_number", length = 20, nullable = false)
    private String plateNumber;

    /**
     * 司机姓名
     */
    @Column(name = "driver_name", length = 50)
    private String driverName;

    /**
     * 司机电话
     */
    @Column(name = "driver_phone", length = 20)
    private String driverPhone;

    /**
     * 车辆载重容量 (kg)
     */
    @Column(name = "capacity", precision = 10, scale = 2)
    private BigDecimal capacity;

    /**
     * 当前装载量 (kg)
     */
    @Column(name = "current_load", precision = 10, scale = 2)
    private BigDecimal currentLoad = BigDecimal.ZERO;

    /**
     * 车辆状态
     * available - 可用
     * loading - 装载中
     * dispatched - 已发车
     * maintenance - 维护中
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private VehicleStatus status = VehicleStatus.available;

    /**
     * 车辆类型
     */
    @Column(name = "vehicle_type", length = 50)
    private String vehicleType;

    /**
     * 备注
     */
    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public enum VehicleStatus {
        available, loading, dispatched, maintenance;

        /**
         * 面向用户的合法取值清单 —— <b>由枚举自己生成，不许再手写第二份</b>。
         *
         * <p>2026-08-19 前 {@code VehicleServiceImpl} 的 400 报错里手写着
         * {@code "available / loading / in_use / maintenance / retired"}，
         * 与本枚举<b>两个方向同时对不上</b>：
         * <ul>
         *   <li>枚举里有的 {@code dispatched} 清单里没有 —— 而 prod 上真有车处在这个状态
         *       （2026-08-19 实测 {@code public.vehicles} 2 行：dispatched 1 / available 1）；</li>
         *   <li>清单里的 {@code in_use} / {@code retired} 枚举里<b>根本没有</b> ——
         *       用户照着报错去填，{@code valueOf()} 抛异常，再吃一个 400。</li>
         * </ul>
         *
         * <p>那份清单就是被抄去前端的源头（web-admin 车辆字典的 statusMap 曾逐字照抄，
         * 见 {@code web-admin/src/views/sales/vehicles/list.vue} 的注释）。
         * ⇒ 形态 D 的正解：能抽成一份就抽成一份，让它<b>漂不起来</b>。
         *
         * <p>⚠️ 分隔符与顺序被 {@code VehicleStatusHintContractTest} 钉着；
         * 它同时钉住 {@code VehicleController} 上那份<b>抽不掉</b>的 Swagger 描述
         * （注解值必须是编译期常量，生成不了）—— 那一份钉的是「两份一致」。
         */
        public static String legalValuesHint() {
            return java.util.Arrays.stream(values())
                    .map(Enum::name)
                    .collect(java.util.stream.Collectors.joining(" / "));
        }
    }

    @PrePersist
    public void prePersist() {
        if (this.id == null) {
            this.id = java.util.UUID.randomUUID().toString();
        }
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
