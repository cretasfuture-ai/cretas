package com.cretas.aims.service.scheduling;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Session;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Objects;
import java.util.TreeSet;

/**
 * 产线排程的事务级互斥: 改某条线上的排程前, 按 (工厂, 产线) 取 PG advisory 事务锁, 提交 / 回滚时自动释放。
 *
 * <p>为什么要它: 改排程的人先读「这条线这个时段空着」再写, 两次之间别人也能读到同样的空档 ——
 * 行锁锁不住「还不存在的那一行」。所有写排程的入口用同一把键 ({@link #key}) 就能互相排队。
 * 现在只有缺料重排在用; APS 自动排程与插单接入它是后续工作。
 *
 * <p>多条线一次取, 按键排序取, 免得两个事务交叉等待。非 PostgreSQL (H2 单测) 时什么都不做。
 */
@Slf4j
@Component
public class ScheduleLineLocks {

    @PersistenceContext
    private EntityManager entityManager;

    private volatile Boolean postgres;

    public ScheduleLineLocks() {
    }

    public ScheduleLineLocks(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** 锁键: 所有写排程的入口必须用同一个写法。 */
    public static String key(String factoryId, String lineId) {
        return "line_schedule:" + factoryId + ":" + lineId;
    }

    /** 在当前事务里锁住这些线 (去重、排序后逐个取)。必须在事务里调用。 */
    public void lock(String factoryId, Collection<String> lineIds) {
        TreeSet<String> keys = new TreeSet<>();
        for (String lineId : lineIds) {
            if (lineId != null) {
                keys.add(key(factoryId, lineId));
            }
        }
        if (keys.isEmpty() || !isPostgres()) {
            return;
        }
        for (String k : keys) {
            entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(:k))")
                    .setParameter("k", k)
                    .getSingleResult();
        }
    }

    private boolean isPostgres() {
        Boolean known = postgres;
        if (known == null) {
            known = entityManager.unwrap(Session.class).doReturningWork(c ->
                    Objects.requireNonNullElse(c.getMetaData().getDatabaseProductName(), "")
                            .toLowerCase(java.util.Locale.ROOT).contains("postgres"));
            if (!known) {
                log.debug("非 PostgreSQL, 产线排程锁不生效");
            }
            postgres = known;
        }
        return known;
    }
}
