package com.cretas.aims.service.impl;

import com.cretas.aims.dto.user.UpdateMyProfileRequest;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.UserMapper;
import com.cretas.aims.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 自助改资料：**只能改自己，且只能改姓名与邮箱**。
 *
 * <h3>为什么有这个方法（2026-08-29 逐屏走查实测）</h3>
 *
 * 「我的 → 编辑 → 保存」对每个角色都可点，而它调的是人事管理的
 * {@code PUT /users/{userId}}（`@RequireModule("hr_employee")` + `hr:read_write`）。
 * 仓储主管点保存拿到 <b>403</b>，提示他去开通整个人事模块的写权限 ——
 * ▎按那句提示做等于把员工名册的增删改一起给出去。
 *
 * <h3>这道闸守的是「不能顺手提权」</h3>
 *
 * 每条正向断言都配一条阴性对照：只断言「能改名字」的话，
 * 一个把整个 {@code CreateUserRequest} 透传下去的实现也能全绿。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("自助改资料：只能改自己的姓名与邮箱")
class UpdateMyProfileSecurityTest {

    private static final String FACTORY = "F006";
    private static final Long ME = 1554L;

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private UserServiceImpl service;

    private static User user(String factoryId, String role, boolean active) {
        User u = new User();
        u.setId(ME);
        u.setFactoryId(factoryId);
        u.setUsername("f006_warehouse_mgr");
        u.setFullName("旧名字");
        u.setEmail("old@example.com");
        u.setRoleCode(role);
        u.setIsActive(active);
        return u;
    }

    private static UpdateMyProfileRequest req(String fullName, String email) {
        UpdateMyProfileRequest r = new UpdateMyProfileRequest();
        r.setFullName(fullName);
        r.setEmail(email);
        return r;
    }

    @Test
    @DisplayName("能改自己的姓名与邮箱")
    void canUpdateOwnNameAndEmail() {
        User u = user(FACTORY, "warehouse_manager", true);
        when(userRepository.findById(ME)).thenReturn(Optional.of(u));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateMyProfile(FACTORY, ME, req("新名字", "new@example.com"));

        assertThat(u.getFullName()).isEqualTo("新名字");
        assertThat(u.getEmail()).isEqualTo("new@example.com");
    }

    @Test
    @DisplayName("⛔ 角色 / 启用状态 / 用户名 一个都不许被这条路径改掉")
    void cannotEscalateThroughSelfEdit() {
        User u = user(FACTORY, "warehouse_manager", true);
        when(userRepository.findById(ME)).thenReturn(Optional.of(u));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateMyProfile(FACTORY, ME, req("新名字", "new@example.com"));

        assertThat(u.getRoleCode()).as("角色").isEqualTo("warehouse_manager");
        assertThat(u.getIsActive()).as("启用状态").isTrue();
        assertThat(u.getUsername()).as("用户名").isEqualTo("f006_warehouse_mgr");
        assertThat(u.getFactoryId()).as("工厂").isEqualTo(FACTORY);
    }

    @Test
    @DisplayName("⛔ 请求体里根本没有提权字段 —— 加字段必须有人动这个 DTO")
    void theRequestDtoHasOnlyTwoFields() {
        String[] names = new String[UpdateMyProfileRequest.class.getDeclaredFields().length];
        int i = 0;
        for (Field f : UpdateMyProfileRequest.class.getDeclaredFields()) {
            names[i++] = f.getName();
        }
        assertThat(names)
                .as("UpdateMyProfileRequest 多了字段 —— 自助改资料的可写面被扩大了，"
                        + "⛔ 角色/工厂/启用状态/用户名/密码/手机号都不该进来")
                .containsExactlyInAnyOrder("fullName", "email");
    }

    @Test
    @DisplayName("跨厂拒绝：token 里的用户不属于路径上的工厂")
    void rejectsCrossFactory() {
        User u = user("F001", "warehouse_manager", true);
        when(userRepository.findById(ME)).thenReturn(Optional.of(u));

        assertThatThrownBy(() -> service.updateMyProfile(FACTORY, ME, req("新名字", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权操作该用户");
    }

    @Test
    @DisplayName("阴性对照：null 表示「这次不改」，⛔ 不许被当成「清空」")
    void nullMeansUnchanged() {
        User u = user(FACTORY, "warehouse_manager", true);
        when(userRepository.findById(ME)).thenReturn(Optional.of(u));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateMyProfile(FACTORY, ME, req(null, null));

        assertThat(u.getFullName()).isEqualTo("旧名字");
        assertThat(u.getEmail()).isEqualTo("old@example.com");
    }
}
