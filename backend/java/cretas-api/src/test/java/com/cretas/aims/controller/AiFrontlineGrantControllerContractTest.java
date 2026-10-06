package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「一线角色 AI 操作权限」接口: 开放 / 关闭只给工厂管理员与权限管理员 (与「功能配置」同一组), 列表登录即可看。
 */
class AiFrontlineGrantControllerContractTest {

    private static Method method(String name) {
        return Arrays.stream(AiFrontlineGrantController.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("开放 / 关闭: @RequireRole 恰好是 factory_super_admin + permission_admin")
    void writesRequireAdminRoles() {
        for (String name : new String[]{"grant", "revoke"}) {
            RequireRole rr = method(name).getAnnotation(RequireRole.class);
            assertThat(rr).as(name).isNotNull();
            assertThat(rr.value()).as(name).containsExactlyInAnyOrder("factory_super_admin", "permission_admin");
        }
        assertThat(method("grant").getAnnotation(PutMapping.class)).isNotNull();
        assertThat(method("revoke").getAnnotation(DeleteMapping.class)).isNotNull();
    }

    @Test
    @DisplayName("对照: 列表不限角色 (只需登录), 与功能配置的 GET 一致")
    void listIsReadable() {
        assertThat(method("list").getAnnotation(GetMapping.class)).isNotNull();
        assertThat(method("list").getAnnotation(RequireRole.class)).isNull();
    }
}
