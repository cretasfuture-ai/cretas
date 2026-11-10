package com.cretas.aims.service.impl;

import com.cretas.aims.entity.Notification;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.enums.NotificationType;
import com.cretas.aims.repository.NotificationRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.canvas.ThresholdKeys;
import com.cretas.aims.service.canvas.ThresholdResolverService;
import com.cretas.aims.service.notification.impl.DbNotificationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工厂纪律 B12 (Steve 2026-11-10): 每类通知发给谁由工厂配置; 找不到接收人时默认发厂长, 不静默丢掉。
 *
 * <p>用一份「内存通讯录」代替 UserRepository, 断言真正落库的通知行 (谁收到了), 而不是断言调用了哪个方法。
 */
@DisplayName("B12 通知接收人: 工厂配置 + 找不到人转发厂长")
class NotificationRecipientRoutingContractTest {

    private static final String F = "F100";

    private NotificationRepository notificationRepository;
    private UserRepository userRepository;
    private ThresholdResolverService thresholds;
    private NotificationServiceImpl service;
    private final Map<String, List<User>> directory = new HashMap<>();
    private final Map<String, String> configured = new HashMap<>();

    @BeforeEach
    void setUp() {
        notificationRepository = mock(NotificationRepository.class);
        userRepository = mock(UserRepository.class);
        thresholds = mock(ThresholdResolverService.class);
        service = new NotificationServiceImpl(notificationRepository, userRepository, thresholds);
        directory.clear();
        configured.clear();
        when(userRepository.findByFactoryIdAndRoleCode(eq(F), anyString())).thenAnswer(inv ->
                new ArrayList<>(directory.getOrDefault((String) inv.getArgument(1), List.of())));
        when(thresholds.getString(eq(F), anyString(), any())).thenAnswer(inv -> {
            String v = configured.get((String) inv.getArgument(1));
            return v != null ? v : inv.getArgument(2);
        });
        when(notificationRepository.saveAll(anyIterable())).thenAnswer(inv -> inv.getArgument(0));
    }

    private void hire(long id, FactoryUserRole role) {
        User u = new User();
        u.setId(id);
        u.setFactoryId(F);
        u.setRoleCode(role.name());
        directory.computeIfAbsent(role.name(), k -> new ArrayList<>()).add(u);
    }

    @SuppressWarnings("unchecked")
    private List<Long> deliveredUserIds() {
        ArgumentCaptor<Iterable<Notification>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(notificationRepository).saveAll(captor.capture());
        List<Long> ids = new ArrayList<>();
        captor.getValue().forEach(n -> ids.add(n.getUserId()));
        return ids;
    }

    @Test
    @DisplayName("一人厂只有厂长: 发给调度员的延期预警 / 排产通知 / 低库存都能送到厂长")
    void soleFactoryHead_receivesEverything() {
        hire(1, FactoryUserRole.factory_super_admin);

        List<Notification> sent = service.sendByPolicy(F, ThresholdKeys.PRODUCTION_NOTIFY_EFFICIENCY_WARNING,
                List.of(FactoryUserRole.dispatcher, FactoryUserRole.workshop_supervisor),
                "生产进度严重落后预警", "内容", NotificationType.WARNING, "SCHEDULING", "S1");

        assertThat(sent).hasSize(1); // 阳性对照: 真的落了一条
        assertThat(deliveredUserIds()).containsExactly(1L);
        assertThat(sent.get(0).getContent()).contains("已转发给厂长");
    }

    @Test
    @DisplayName("一人厂 + 角色本来就含厂长: 一个人只收一条, 不重复")
    void sameUserInTwoRoles_getsOneNotification() {
        hire(1, FactoryUserRole.factory_super_admin);

        service.sendByPolicy(F, ThresholdKeys.PRODUCTION_NOTIFY_SCHEDULE_CREATED,
                List.of(FactoryUserRole.dispatcher, FactoryUserRole.factory_super_admin),
                "新调度计划已创建", "内容", NotificationType.INFO, "SCHEDULING", "S1");

        assertThat(deliveredUserIds()).containsExactly(1L);
    }

    @Test
    @DisplayName("收窄: 低库存只发仓储主管 + 采购主管, 车间主任 / 调度员收不到")
    void otherRolesDoNotReceive() {
        hire(1, FactoryUserRole.factory_super_admin);
        hire(2, FactoryUserRole.warehouse_manager);
        hire(3, FactoryUserRole.procurement_manager);
        hire(4, FactoryUserRole.workshop_supervisor);
        hire(5, FactoryUserRole.dispatcher);

        service.sendByPolicy(F, ThresholdKeys.INVENTORY_NOTIFY_LOW_STOCK,
                List.of(FactoryUserRole.warehouse_manager, FactoryUserRole.procurement_manager),
                "低库存", "内容", NotificationType.WARNING, "LOW_STOCK_ALERT", "M1");

        assertThat(deliveredUserIds()).containsExactlyInAnyOrder(2L, 3L);
    }

    @Test
    @DisplayName("工厂配置覆盖默认: 低库存改成只发厂长 ⇒ 仓储主管不再收到")
    void factoryConfigurationOverridesDefault() {
        hire(1, FactoryUserRole.factory_super_admin);
        hire(2, FactoryUserRole.warehouse_manager);
        configured.put(ThresholdKeys.INVENTORY_NOTIFY_LOW_STOCK, "factory_super_admin");

        service.sendByPolicy(F, ThresholdKeys.INVENTORY_NOTIFY_LOW_STOCK,
                List.of(FactoryUserRole.warehouse_manager, FactoryUserRole.procurement_manager),
                "低库存", "内容", NotificationType.WARNING, "LOW_STOCK_ALERT", "M1");

        assertThat(deliveredUserIds()).containsExactly(1L);
    }

    @Test
    @DisplayName("配置里的角色码写错 / 全空: 忽略错的, 全错就用默认, 不会变成没人收")
    void invalidConfigurationFallsBackToDefault() {
        hire(2, FactoryUserRole.warehouse_manager);
        configured.put(ThresholdKeys.INVENTORY_NOTIFY_LOW_STOCK, "仓管主管, ,nobody");

        service.sendByPolicy(F, ThresholdKeys.INVENTORY_NOTIFY_LOW_STOCK,
                List.of(FactoryUserRole.warehouse_manager),
                "低库存", "内容", NotificationType.WARNING, "LOW_STOCK_ALERT", "M1");

        assertThat(deliveredUserIds()).containsExactly(2L);
    }

    @Test
    @DisplayName("sendToRole 老入口同样兜底: 该角色没有人 ⇒ 转发厂长 (订单成本告警 / ECN 通知等)")
    void legacySendToRoleAlsoFallsBackToFactoryHead() {
        hire(1, FactoryUserRole.factory_super_admin);

        List<Notification> sent = service.sendToRole(F, FactoryUserRole.sales_manager,
                "成本告警", "内容", NotificationType.ALERT, "COST", "O1");

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).getUserId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("厂长也没有人: 才会丢, 返回空且不写库 (有 ERROR 日志)")
    void nobodyAtAll_returnsEmptyWithoutWriting() {
        List<Notification> sent = service.sendToRole(F, FactoryUserRole.dispatcher,
                "x", "y", NotificationType.INFO, "S", "1");

        assertThat(sent).isEmpty();
        verify(notificationRepository, never()).saveAll(anyIterable());
    }

    @Test
    @DisplayName("DbNotificationServiceImpl.notifyRole: 角色没人 ⇒ 改成发给厂长这个角色; 有人 ⇒ 原样")
    void dbNotifyRoleFallsBackToFactoryHeadRole() {
        NotificationRepository repo = mock(NotificationRepository.class);
        UserRepository users = mock(UserRepository.class);
        DbNotificationServiceImpl db = new DbNotificationServiceImpl(repo, users);
        when(users.findByFactoryIdAndRoleCodeIgnoreCase(F, "dispatcher")).thenReturn(List.of());
        User qa = new User();
        qa.setId(9L);
        when(users.findByFactoryIdAndRoleCodeIgnoreCase(F, "quality_manager")).thenReturn(List.of(qa));

        db.notifyRole(F, "dispatcher", "T", "B");
        db.notifyRole(F, "quality_manager", "T2", "B2");

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(repo, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getTargetRole()).isEqualTo("factory_super_admin");
        assertThat(captor.getAllValues().get(0).getContent()).contains("已转发给厂长");
        assertThat(captor.getAllValues().get(1).getTargetRole()).isEqualTo("quality_manager");
    }

    @Test
    @DisplayName("🔴 大写角色码 QUALITY_MANAGER (冷链告警等调用方就这么传) + 库里真有这个人 ⇒ 送给质量经理, 不兜底给厂长")
    void dbNotifyRoleUppercaseCodeReachesTheRealRoleHolder() {
        NotificationRepository repo = mock(NotificationRepository.class);
        // 用「真的区分大小写」的通讯录: 库里存小写; 只有忽略大小写的查询找得到, 精确匹配查询找不到
        UserRepository users = mock(UserRepository.class);
        User qa = new User();
        qa.setId(9L);
        qa.setRoleCode("quality_manager");
        when(users.findByFactoryIdAndRoleCodeIgnoreCase(eq(F), anyString())).thenAnswer(inv ->
                "quality_manager".equalsIgnoreCase(inv.getArgument(1)) ? List.of(qa) : List.of());
        when(users.findByFactoryIdAndRoleCode(F, "QUALITY_MANAGER")).thenReturn(List.of()); // 精确匹配的老路: 找不到
        DbNotificationServiceImpl db = new DbNotificationServiceImpl(repo, users);

        db.notifyRole(F, "QUALITY_MANAGER", "冷链告警", "温度超限");

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getTargetRole()).isEqualTo("QUALITY_MANAGER"); // 没被改成厂长
        assertThat(captor.getValue().getContent()).doesNotContain("已转发给厂长");
    }
}
