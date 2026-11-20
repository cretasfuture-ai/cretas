package com.cretas.aims.service.notification.impl;

import com.cretas.aims.entity.Notification;
import com.cretas.aims.entity.enums.NotificationType;
import com.cretas.aims.repository.NotificationRepository;
import com.cretas.aims.service.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DB-backed notification service — persists notifications to the {@code notifications} table.
 *
 * <p>Replaces {@link LoggingNotificationServiceImpl} which only logged.
 * All callers (FmrExpiryScanner, SampleApprovedEventListener, etc.) now
 * produce real stored notifications visible via the REST API
 * ({@code GET /api/mobile/{factoryId}/notifications}).
 *
 * <p>Design:
 * <ul>
 *   <li>{@link #notifyUser} saves with userId set, targetRole=null</li>
 *   <li>{@link #notifyRole} saves with userId=null, targetRole set
 *       (single row, role-targeted — controller queries include role-based rows)</li>
 *   <li>{@link #broadcastFactory} saves with userId=null, targetRole=null (factory-wide broadcast)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DbNotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final com.cretas.aims.repository.UserRepository userRepository;

    @Override
    @Transactional
    public void notifyRole(String factoryId, String roleCode, String title, String body) {
        log.info("[Notification] factory={} role={} title={}", factoryId, roleCode, title);

        // 这个角色在本工厂一个人都没有 ⇒ 发给厂长, 不留一条没人看得到的通知 (一人厂只有厂长)。
        String targetRole = roleCode;
        String content = body;
        String head = com.cretas.aims.entity.enums.FactoryUserRole.factory_super_admin.name();
        if (roleCode != null && !head.equalsIgnoreCase(roleCode)
                && userRepository.findByFactoryIdAndRoleCodeIgnoreCase(factoryId, roleCode).isEmpty()) {
            targetRole = head;
            content = body + "\n(原定接收岗位 " + roleCode + " 没有人, 已转发给厂长)";
            log.warn("[Notification] 角色没有人, 改发厂长: factory={} role={} title={}", factoryId, roleCode, title);
        }

        Notification notification = Notification.builder()
                .factoryId(factoryId)
                .userId(null)
                .targetRole(targetRole)
                .title(title)
                .content(content)
                .type(NotificationType.INFO)
                .source("SYSTEM")
                .isRead(false)
                .build();

        notificationRepository.save(notification);
    }

    @Override
    @Transactional
    public void notifyUser(String factoryId, Long userId, String title, String body) {
        log.info("[Notification] factory={} user={} title={}", factoryId, userId, title);

        Notification notification = Notification.builder()
                .factoryId(factoryId)
                .userId(userId)
                .targetRole(null)
                .title(title)
                .content(body)
                .type(NotificationType.INFO)
                .source("SYSTEM")
                .isRead(false)
                .build();

        notificationRepository.save(notification);
    }

    @Override
    @Transactional
    public void broadcastFactory(String factoryId, String title, String body) {
        log.info("[Notification BROADCAST] factory={} title={}", factoryId, title);

        Notification notification = Notification.builder()
                .factoryId(factoryId)
                .userId(null)
                .targetRole(null)
                .title(title)
                .content(body)
                .type(NotificationType.SYSTEM)
                .source("SYSTEM")
                .isRead(false)
                .build();

        notificationRepository.save(notification);
    }
}
