package com.cretas.aims.entity.crew;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 每人每厂一行的交办锁 (V20261115_61): 交办前在同一事务里 SELECT ... FOR UPDATE 锁住这一行, 再数「已交办」件数、再插入,
 * 同一个人的并发交办因此串行 —— 每人上限 ({@code DigitalCrewService.OPEN_DELEGATIONS_PER_USER}) 不会被并发冲破
 * (#1771 复审 低-1)。行本身不存业务数据, 只当锁用; 第一次交办时建。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "digital_crew_delegation_locks")
public class DigitalCrewDelegationLock {

    /** factory_id + ":" + user_id。 */
    @Id
    @Column(name = "lock_key", length = 80, nullable = false)
    private String lockKey;

    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static String key(String factoryId, Long userId) {
        return factoryId + ":" + userId;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
