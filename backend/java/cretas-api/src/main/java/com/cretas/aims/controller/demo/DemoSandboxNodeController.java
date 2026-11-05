package com.cretas.aims.controller.demo;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.demo.DemoSandboxGatewayService;
import com.cretas.aims.service.demo.DemoSandboxNodeGuardConfiguration.DemoSandboxNodeGuard;
import com.cretas.aims.utils.TokenUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Internal authenticated identity endpoint for sandbox prepare hooks. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mobile/{factoryId}/demo-sandbox-node")
@Tag(name = "F006 Demo Sandbox Node")
public class DemoSandboxNodeController {

    private final MobileService mobileService;
    private final UserRepository userRepository;
    private final ObjectProvider<DemoSandboxNodeGuard> nodeGuard;

    @GetMapping("/identity")
    @Operation(summary = "读取 F006 演示沙盒节点身份")
    public ApiResponse<Map<String, Object>> identity(
            @PathVariable String factoryId,
            @RequestHeader("Authorization") String authorization) {
        currentActor(factoryId, authorization);
        DemoSandboxNodeGuard guard = nodeGuard.getIfAvailable();
        if (guard == null) {
            throw new BusinessException(503, "演示沙盒节点未启用")
                    .withCode("DEMO_SANDBOX_NODE_DISABLED");
        }
        return ApiResponse.success(guard.identity());
    }

    private User currentActor(String factoryId, String authorization) {
        if (!DemoSandboxGatewayService.FACTORY_ID.equals(factoryId)) {
            throw new BusinessException(403, "演示沙盒节点仅限 F006 工厂")
                    .withCode("DEMO_SANDBOX_FACTORY_REQUIRED");
        }
        try {
            String token = TokenUtils.extractToken(authorization);
            UserDTO tokenUser = mobileService.getUserFromToken(token);
            User actor = tokenUser == null || tokenUser.getId() == null
                    ? null : userRepository.findById(tokenUser.getId()).orElse(null);
            if (actor == null) {
                throw new BusinessException(401, "未授权 — JWT 解析失败");
            }
            if (!factoryId.equals(actor.getFactoryId())) {
                throw new BusinessException(403, "演示沙盒节点仅限 F006 工厂")
                        .withCode("DEMO_SANDBOX_FACTORY_REQUIRED");
            }
            if (!DemoSandboxGatewayService.REQUIRED_ROLE.equals(actor.getRoleCode())) {
                throw new BusinessException(403, "演示沙盒节点仅限工厂超级管理员")
                        .withCode("DEMO_SANDBOX_ROLE_REQUIRED");
            }
            if (actor.getId() != DemoSandboxGatewayService.DEMO_ACTOR_ID) {
                throw new BusinessException(403, "演示沙盒节点仅限 f006_admin 演示账号")
                        .withCode("DEMO_SANDBOX_ACTOR_REQUIRED");
            }
            return actor;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(401, "未授权 — JWT 解析失败", e)
                    .withCode("DEMO_SANDBOX_AUTH_REQUIRED");
        }
    }
}
