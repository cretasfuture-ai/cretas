package com.cretas.aims.repository;

import com.cretas.aims.entity.Notification;
import com.cretas.aims.entity.enums.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工厂纪律 B12: 「发给某个角色」的通知 (userId 为空 + targetRole 有值) 原来被
 * {@code userId = :userId OR userId IS NULL} 一股脑推给全厂每个人。收窄后: 只有该角色 (和没指定角色的广播)
 * 才看得到、算进未读数、被「全部已读」影响。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("NotificationRepository — 角色通知不再推给全厂")
class NotificationRoleFilterRepositoryTest {

    private static final String F = "F-NRF-1";

    @Autowired
    private NotificationRepository repo;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager em;

    private Long dispatcherId;
    private Long supervisorId;

    private Long user(String name, String role) {
        var u = new com.cretas.aims.entity.User();
        u.setFactoryId(F);
        u.setUsername(name);
        u.setPasswordHash("unused");
        u.setIsActive(true);
        u.setRoleCode(role);
        em.persist(u);
        em.flush();
        return u.getId();
    }

    private Notification row(Long userId, String targetRole, String title) {
        return Notification.builder().factoryId(F).userId(userId).targetRole(targetRole)
                .title(title).content("c").type(NotificationType.INFO).source("SYSTEM").isRead(false).build();
    }

    private void seed() {
        var f = new com.cretas.aims.entity.Factory();
        f.setId(F);
        f.setName(F);
        em.persist(f);
        dispatcherId = user("nrf-dispatcher", "dispatcher");
        supervisorId = user("nrf-supervisor", "workshop_supervisor");
        repo.save(row(null, "dispatcher", "给调度员的"));
        repo.save(row(null, null, "全厂广播"));
        repo.save(row(dispatcherId, null, "给 11 号的"));
        repo.save(row(supervisorId, null, "给 12 号的"));
        em.flush();
    }

    @Test
    @DisplayName("调度员: 看得到调度员通知 + 广播 + 自己的; 看不到别人的")
    void dispatcherSeesOwnRoleBroadcastAndPersonal() {
        seed();
        var page = repo.findForUserUnreadFirst(F, dispatcherId, "dispatcher", PageRequest.of(0, 20));
        var titles = page.getContent().stream().map(Notification::getTitle).toList();
        assertEquals(3, titles.size(), titles.toString()); // 阳性对照: 确实查到了东西
        assertTrue(titles.contains("给调度员的") && titles.contains("全厂广播") && titles.contains("给 11 号的"));
    }

    @Test
    @DisplayName("车间主任: 看不到给调度员的通知, 未读数不含它")
    void otherRoleDoesNotSeeDispatcherNotification() {
        seed();
        var titles = repo.findForUserUnreadFirst(F, supervisorId, "workshop_supervisor", PageRequest.of(0, 20))
                .getContent().stream().map(Notification::getTitle).toList();
        assertEquals(2, titles.size(), titles.toString());
        assertTrue(titles.contains("全厂广播") && titles.contains("给 12 号的"));
        assertEquals(2, repo.countUnreadForUser(F, supervisorId, "workshop_supervisor"));
        assertEquals(3, repo.countUnreadForUser(F, dispatcherId, "DISPATCHER")); // 角色码大小写不敏感
        assertEquals(2, repo.findUnreadForUser(F, supervisorId, "workshop_supervisor").size());
    }

    @Test
    @DisplayName("车间主任点「全部已读」不会把给调度员的通知标成已读")
    void markAllReadDoesNotTouchOtherRolesNotifications() {
        seed();
        repo.markAllAsReadForUser(F, supervisorId, "workshop_supervisor", LocalDateTime.now());
        // 车间主任这次标已读的是 广播 + 12 号自己的 两条; 给调度员的那条必须仍是未读
        // (调度员 11 号剩: 给调度员的 + 给 11 号的 = 2)
        assertEquals(2, repo.countUnreadForUser(F, dispatcherId, "dispatcher"));
    }
}
