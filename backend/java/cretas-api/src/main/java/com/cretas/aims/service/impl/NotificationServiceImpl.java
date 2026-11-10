package com.cretas.aims.service.impl;

import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.Notification;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.enums.NotificationType;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.NotificationRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.NotificationService;
import com.cretas.aims.service.canvas.ThresholdKeys;
import com.cretas.aims.service.canvas.ThresholdResolverService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 通知服务实现
 * 提供调度预案、告警、系统消息等的 App 内推送通知功能
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-30
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final ThresholdResolverService thresholdResolverService;

    // ==================== 发送通知 ====================

    @Override
    @Transactional
    public Notification sendNotification(String factoryId, Long userId, String title, String content,
                                          NotificationType type, String source, String sourceId) {
        log.info("发送通知: factoryId={}, userId={}, title={}, source={}", factoryId, userId, title, source);

        Notification notification = Notification.builder()
                .factoryId(factoryId)
                .userId(userId)
                .title(title)
                .content(content)
                .type(type)
                .source(source)
                .sourceId(sourceId)
                .isRead(false)
                .build();

        return notificationRepository.save(notification);
    }

    @Override
    @Transactional
    public Notification sendToAllUsers(String factoryId, String title, String content,
                                        NotificationType type, String source, String sourceId) {
        log.info("发送广播通知: factoryId={}, title={}, source={}", factoryId, title, source);

        // userId=null 表示发送给工厂所有用户
        Notification notification = Notification.builder()
                .factoryId(factoryId)
                .userId(null)
                .title(title)
                .content(content)
                .type(type)
                .source(source)
                .sourceId(sourceId)
                .isRead(false)
                .build();

        return notificationRepository.save(notification);
    }

    @Override
    @Transactional
    public List<Notification> sendToRole(String factoryId, FactoryUserRole role, String title,
                                          String content, NotificationType type, String source, String sourceId) {
        return sendToRoles(factoryId, List.of(role), title, content, type, source, sourceId);
    }

    /**
     * 发给一组角色: 按用户去重 (一个人身兼多个角色只收一条); 这些角色一个人都没有时改发厂长, 不静默丢弃
     * (一人厂只有厂长时, 所有通知都落到厂长; 厂长也没有人才会丢, 并打 ERROR 日志)。
     */
    @Override
    @Transactional
    public List<Notification> sendToRoles(String factoryId, List<FactoryUserRole> roles, String title,
                                           String content, NotificationType type, String source, String sourceId) {
        log.info("发送角色通知: factoryId={}, roles={}, title={}", factoryId, roles, title);

        Map<Long, User> recipients = new LinkedHashMap<>();
        for (FactoryUserRole role : roles) {
            for (User user : userRepository.findByFactoryIdAndRoleCode(factoryId, role.name())) {
                recipients.putIfAbsent(user.getId(), user);
            }
        }

        String finalContent = content;
        if (recipients.isEmpty() && !roles.contains(FactoryUserRole.factory_super_admin)) {
            for (User user : userRepository.findByFactoryIdAndRoleCode(
                    factoryId, FactoryUserRole.factory_super_admin.name())) {
                recipients.putIfAbsent(user.getId(), user);
            }
            if (!recipients.isEmpty()) {
                finalContent = content + "\n(原定接收岗位 " + roles.stream().map(Enum::name)
                        .collect(Collectors.joining("/")) + " 没有人, 已转发给厂长)";
                log.warn("通知找不到接收人, 改发厂长: factoryId={}, roles={}, title={}", factoryId, roles, title);
            }
        }
        if (recipients.isEmpty()) {
            log.error("通知无人可发 (厂长也没有): factoryId={}, roles={}, title={}", factoryId, roles, title);
            return new ArrayList<>();
        }

        String body = finalContent;
        List<Notification> notifications = recipients.values().stream()
                .map(user -> Notification.builder()
                        .factoryId(factoryId)
                        .userId(user.getId())
                        .title(title)
                        .content(body)
                        .type(type)
                        .source(source)
                        .sourceId(sourceId)
                        .isRead(false)
                        .build())
                .collect(Collectors.toList());

        return notificationRepository.saveAll(notifications);
    }

    /**
     * 每类通知发给谁由工厂配置 (factory_thresholds, key 见 ThresholdKeys 的 *_NOTIFY_*), 值是逗号分隔的角色码;
     * 没配或一个角色都认不出时用 defaultRoles。
     */
    @Override
    @Transactional
    public List<Notification> sendByPolicy(String factoryId, String policyKey, List<FactoryUserRole> defaultRoles,
                                            String title, String content, NotificationType type,
                                            String source, String sourceId) {
        List<FactoryUserRole> roles = resolveRecipientRoles(factoryId, policyKey, defaultRoles);
        return sendToRoles(factoryId, roles, title, content, type, source, sourceId);
    }

    private List<FactoryUserRole> resolveRecipientRoles(String factoryId, String policyKey,
                                                         List<FactoryUserRole> defaultRoles) {
        String raw = thresholdResolverService.getString(factoryId, policyKey, null);
        if (raw == null || raw.isBlank()) {
            return defaultRoles;
        }
        List<FactoryUserRole> roles = new ArrayList<>();
        for (String code : raw.split(",")) {
            String trimmed = code.trim().toLowerCase();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                FactoryUserRole role = FactoryUserRole.valueOf(trimmed);
                if (!roles.contains(role)) {
                    roles.add(role);
                }
            } catch (IllegalArgumentException unknown) {
                log.warn("通知接收角色配置里有不认识的角色码, 已忽略: factoryId={}, key={}, code={}",
                        factoryId, policyKey, code);
            }
        }
        return roles.isEmpty() ? defaultRoles : roles;
    }

    @Override
    @Transactional
    public List<Notification> sendToUsers(String factoryId, List<Long> userIds, String title,
                                           String content, NotificationType type, String source, String sourceId) {
        log.info("发送多用户通知: factoryId={}, userIds={}, title={}", factoryId, userIds, title);

        List<Notification> notifications = userIds.stream()
                .map(userId -> Notification.builder()
                        .factoryId(factoryId)
                        .userId(userId)
                        .title(title)
                        .content(content)
                        .type(type)
                        .source(source)
                        .sourceId(sourceId)
                        .isRead(false)
                        .build())
                .collect(Collectors.toList());

        return notificationRepository.saveAll(notifications);
    }

    // ==================== 调度专用通知 ====================

    @Override
    @Transactional
    public void notifyScheduleCreated(String factoryId, String scheduleId, Map<String, Object> planDetails) {
        String title = "新调度计划已创建";
        String content = buildScheduleCreatedContent(planDetails);

        // 默认调度员 + 厂长, 工厂可配置
        sendByPolicy(factoryId, ThresholdKeys.PRODUCTION_NOTIFY_SCHEDULE_CREATED,
                List.of(FactoryUserRole.dispatcher, FactoryUserRole.factory_super_admin),
                title, content, NotificationType.INFO, "SCHEDULING", scheduleId);

        log.info("调度计划创建通知已发送: scheduleId={}", scheduleId);
    }

    @Override
    @Transactional
    public void notifyScheduleConfirmed(String factoryId, String scheduleId, String confirmedBy) {
        String title = "调度计划已确认";
        String content = String.format("调度计划 %s 已由 %s 确认执行。", scheduleId, confirmedBy);

        // 默认车间主任, 工厂可配置
        sendByPolicy(factoryId, ThresholdKeys.PRODUCTION_NOTIFY_SCHEDULE_CONFIRMED,
                List.of(FactoryUserRole.workshop_supervisor),
                title, content, NotificationType.SUCCESS, "SCHEDULING", scheduleId);

        log.info("调度计划确认通知已发送: scheduleId={}", scheduleId);
    }

    @Override
    @Transactional
    public void notifyScheduleDelayed(String factoryId, String scheduleId, String delayReason, double efficiency) {
        String title = "调度计划延期预警";
        String content = String.format(
                "调度计划 %s 出现延期风险。\n原因: %s\n当前效率: %.1f%%\n请及时采取措施。",
                scheduleId, delayReason, efficiency * 100);

        // 默认调度员、厂长和车间主任, 工厂可配置
        sendByPolicy(factoryId, ThresholdKeys.PRODUCTION_NOTIFY_SCHEDULE_DELAYED,
                List.of(FactoryUserRole.dispatcher, FactoryUserRole.factory_super_admin,
                        FactoryUserRole.workshop_supervisor),
                title, content, NotificationType.WARNING, "SCHEDULING", scheduleId);

        log.info("调度计划延期通知已发送: scheduleId={}, efficiency={}", scheduleId, efficiency);
    }

    @Override
    @Transactional
    public void notifyUrgentInsert(String factoryId, String scheduleId, String impactSummary) {
        String title = "紧急插单通知";
        String content = String.format(
                "紧急插单已执行，计划ID: %s\n影响评估: %s",
                scheduleId, impactSummary);

        // 默认调度员、厂长和车间主任, 工厂可配置
        sendByPolicy(factoryId, ThresholdKeys.PRODUCTION_NOTIFY_URGENT_INSERT,
                List.of(FactoryUserRole.dispatcher, FactoryUserRole.factory_super_admin,
                        FactoryUserRole.workshop_supervisor),
                title, content, NotificationType.ALERT, "SCHEDULING", scheduleId);

        log.info("紧急插单通知已发送: scheduleId={}", scheduleId);
    }

    // ==================== 查询通知 ====================

    @Override
    public PageResponse<Notification> getNotifications(String factoryId, Long userId, PageRequest pageRequest) {
        log.info("查询通知: factoryId={}, userId={}", factoryId, userId);

        Pageable pageable = org.springframework.data.domain.PageRequest.of(
                pageRequest.getPage() - 1,
                pageRequest.getSize(),
                Sort.by(Sort.Direction.DESC, "createdAt")
        );

        Page<Notification> page;
        if (userId != null) {
            // 查询指定用户的通知 + 广播通知 (userId=null)
            page = notificationRepository.findByFactoryIdAndUserIdOrderByCreatedAtDesc(factoryId, userId, pageable);
        } else {
            page = notificationRepository.findByFactoryIdOrderByCreatedAtDesc(factoryId, pageable);
        }

        return PageResponse.of(
                page.getContent(),
                pageRequest.getPage(),
                pageRequest.getSize(),
                page.getTotalElements()
        );
    }

    @Override
    public long getUnreadCount(String factoryId, Long userId) {
        if (userId != null) {
            return notificationRepository.countByFactoryIdAndUserIdAndIsReadFalse(factoryId, userId);
        }
        return notificationRepository.countByFactoryIdAndIsReadFalse(factoryId);
    }

    @Override
    public List<Notification> getRecentNotifications(String factoryId, int limit) {
        return notificationRepository.findTop10ByFactoryIdOrderByCreatedAtDesc(factoryId);
    }

    // ==================== 更新通知 ====================

    @Override
    @Transactional
    public void markAsRead(String factoryId, Long notificationId) {
        Notification notification = notificationRepository.findByFactoryIdAndId(factoryId, notificationId)
                .orElseThrow(() -> new ResourceNotFoundException("通知不存在: " + notificationId));

        notification.setIsRead(true);
        notification.setReadAt(LocalDateTime.now());
        notificationRepository.save(notification);

        log.info("通知已标记为已读: notificationId={}", notificationId);
    }

    @Override
    @Transactional
    public int markAllAsRead(String factoryId, Long userId) {
        int count;
        if (userId != null) {
            count = notificationRepository.markAllAsReadByUser(factoryId, userId, LocalDateTime.now());
        } else {
            count = notificationRepository.markAllAsRead(factoryId, LocalDateTime.now());
        }

        log.info("批量标记已读: factoryId={}, userId={}, count={}", factoryId, userId, count);
        return count;
    }

    @Override
    @Transactional
    public void deleteNotification(String factoryId, Long notificationId) {
        Notification notification = notificationRepository.findByFactoryIdAndId(factoryId, notificationId)
                .orElseThrow(() -> new ResourceNotFoundException("通知不存在: " + notificationId));

        notificationRepository.delete(notification);
        log.info("通知已删除: notificationId={}", notificationId);
    }

    // ==================== 辅助方法 ====================

    private String buildScheduleCreatedContent(Map<String, Object> planDetails) {
        StringBuilder sb = new StringBuilder();
        sb.append("新的调度计划已创建:\n");

        if (planDetails.containsKey("planName")) {
            sb.append("计划名称: ").append(planDetails.get("planName")).append("\n");
        }
        if (planDetails.containsKey("productName")) {
            sb.append("产品: ").append(planDetails.get("productName")).append("\n");
        }
        if (planDetails.containsKey("quantity")) {
            sb.append("计划产量: ").append(planDetails.get("quantity")).append("\n");
        }
        if (planDetails.containsKey("deadline")) {
            sb.append("截止时间: ").append(planDetails.get("deadline")).append("\n");
        }
        if (planDetails.containsKey("probability")) {
            sb.append("预计完成概率: ").append(planDetails.get("probability")).append("%\n");
        }

        return sb.toString();
    }
}
