package com.cretas.aims.controller.demo;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.demo.DemoSandboxGatewayService;
import com.cretas.aims.utils.TokenUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/** Authenticated F006 gateway for the isolated, resettable demo sandbox. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mobile/{factoryId}/demo-sandbox")
@Tag(name = "F006 Demo Sandbox")
public class DemoSandboxController {

    private final DemoSandboxGatewayService gateway;
    private final MobileService mobileService;
    private final UserRepository userRepository;

    @PostMapping("/sessions")
    @Operation(summary = "创建 F006 演示沙盒会话")
    public ApiResponse<Map<String, Object>> create(
            @PathVariable String factoryId,
            @RequestHeader("Authorization") String authorization) {
        return ApiResponse.success(gateway.create(currentActor(factoryId, authorization)));
    }

    @GetMapping("/sessions/{sessionId}")
    @Operation(summary = "查询 F006 演示沙盒会话")
    public ApiResponse<Map<String, Object>> get(
            @PathVariable String factoryId,
            @PathVariable String sessionId,
            @RequestHeader("Authorization") String authorization) {
        return ApiResponse.success(gateway.get(currentFactoryActor(factoryId, authorization), sessionId));
    }

    @DeleteMapping("/sessions/{sessionId}")
    @Operation(summary = "结束并清理 F006 演示沙盒会话")
    public ApiResponse<Map<String, Object>> end(
            @PathVariable String factoryId,
            @PathVariable String sessionId,
            @RequestHeader("Authorization") String authorization) {
        return ApiResponse.success(gateway.end(currentFactoryActor(factoryId, authorization), sessionId));
    }

    @GetMapping("/active-session")
    @Operation(summary = "恢复当前 F006 演示沙盒会话")
    public ApiResponse<Map<String, Object>> activeSession(
            @PathVariable String factoryId,
            @RequestHeader("Authorization") String authorization) {
        Optional<Map<String, Object>> active = gateway.activeSession(currentFactoryActor(factoryId, authorization));
        return ApiResponse.success(active.orElse(null));
    }

    /**
     * Proxy preserves the manager's raw status and body.  No redirect or
     * authentication cookie is ever copied across this boundary.
     */
    @RequestMapping("/sessions/{sessionId}/proxy/**")
    @Operation(summary = "代理 F006 演示沙盒 API")
    public ResponseEntity<byte[]> proxy(
            @PathVariable String factoryId,
            @PathVariable String sessionId,
            @RequestHeader("Authorization") String authorization,
            HttpServletRequest request) {
        User actor = currentFactoryActor(factoryId, authorization);
        String rawTargetPath = rawTargetPath(request);
        byte[] body = readBody(request);
        DemoSandboxGatewayService.ProxyResult result = gateway.proxy(
                actor,
                sessionId,
                rawTargetPath,
                request.getQueryString(),
                request.getMethod(),
                request.getHeader("Idempotency-Key"),
                request.getContentType(),
                body);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(result.status());
        if (result.contentType() != null && !result.contentType().isBlank()) {
            try {
                response.contentType(MediaType.parseMediaType(result.contentType()));
            } catch (IllegalArgumentException ignored) {
                response.contentType(MediaType.APPLICATION_OCTET_STREAM);
            }
        }
        return response.body(result.body());
    }

    private User currentActor(String factoryId, String authorization) {
        User actor = currentFactoryActor(factoryId, authorization);
        if (!DemoSandboxGatewayService.REQUIRED_ROLE.equals(actor.getRoleCode())) {
            throw new BusinessException(403, "演示沙盒仅限工厂超级管理员")
                    .withCode("DEMO_SANDBOX_ROLE_REQUIRED");
        }
        return actor;
    }

    private User currentFactoryActor(String factoryId, String authorization) {
        if (!DemoSandboxGatewayService.FACTORY_ID.equals(factoryId)) {
            throw new BusinessException(403, "演示沙盒仅限 F006 工厂")
                    .withCode("DEMO_SANDBOX_FACTORY_REQUIRED");
        }
        try {
            String token = TokenUtils.extractToken(authorization);
            com.cretas.aims.dto.user.UserDTO tokenUser = mobileService.getUserFromToken(token);
            if (tokenUser == null || tokenUser.getId() == null) {
                throw new BusinessException(401, "未授权 — JWT 解析失败");
            }
            User actor = userRepository.findById(tokenUser.getId()).orElse(null);
            if (actor == null) {
                throw new BusinessException(401, "未授权 — 用户不存在");
            }
            if (!factoryId.equals(actor.getFactoryId())
                    || !DemoSandboxGatewayService.FACTORY_ID.equals(actor.getFactoryId())) {
                throw new BusinessException(403, "演示沙盒仅限 F006 工厂")
                        .withCode("DEMO_SANDBOX_FACTORY_REQUIRED");
            }
            return actor;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(401, "未授权 — JWT 解析失败", e)
                    .withCode("DEMO_SANDBOX_AUTH_REQUIRED");
        }
    }

    private String rawTargetPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String marker = "/proxy";
        int markerIndex = uri == null ? -1 : uri.indexOf(marker);
        if (markerIndex < 0) {
            throw new BusinessException(400, "演示沙盒缺少代理目标路径")
                    .withCode("DEMO_SANDBOX_TARGET_PATH_INVALID");
        }
        return uri.substring(markerIndex + marker.length());
    }

    private byte[] readBody(HttpServletRequest request) {
        try {
            if (request.getContentLengthLong() > DemoSandboxGatewayService.MAX_REQUEST_BODY_BYTES) {
                throw new BusinessException(413, "演示沙盒请求体不能超过 16 MiB")
                        .withCode("DEMO_SANDBOX_REQUEST_TOO_LARGE");
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream(
                    Math.max(0, Math.min(request.getContentLength(), 8192)));
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = request.getInputStream().read(buffer)) != -1) {
                total += read;
                if (total > DemoSandboxGatewayService.MAX_REQUEST_BODY_BYTES) {
                    throw new BusinessException(413, "演示沙盒请求体不能超过 16 MiB")
                            .withCode("DEMO_SANDBOX_REQUEST_TOO_LARGE");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException(400, "读取演示沙盒请求体失败", e)
                    .withCode("DEMO_SANDBOX_REQUEST_BODY_INVALID");
        }
    }
}
