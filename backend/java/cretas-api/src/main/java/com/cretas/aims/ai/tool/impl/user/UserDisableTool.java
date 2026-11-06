package com.cretas.aims.ai.tool.impl.user;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.UserService;
import com.cretas.aims.service.UserModuleAccessService;
import com.cretas.aims.service.config.FactoryConfigService;
import com.cretas.aims.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 用户禁用工具
 *
 * 禁用指定用户账号，使其无法登录系统。
 * 仅支持通过确认快照中的 targetUserId 定位用户。
 * 调用 UserService.deactivateUser 执行实际禁用。
 *
 * Intent Code: USER_DISABLE
 *
 * @author Cretas Team
 * @version 3.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class UserDisableTool extends AbstractBusinessTool {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private UserModuleAccessService userModuleAccessService;

    @Autowired
    private FactoryConfigService factoryConfigService;

    @Override
    public String getToolName() {
        return "user_disable";
    }

    @Override
    public String getDescription() {
        return "禁用用户账号。禁用后用户将无法登录系统，可后续重新启用。" +
                "仅支持确认快照中的 targetUserId 指定目标用户。" +
                "适用场景：员工离职、账号安全问题、临时停用账号。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> targetUserId = new HashMap<>();
        targetUserId.put("type", "integer");
        targetUserId.put("description", "确认后要禁用的目标用户ID；不能填写操作者自己的ID");
        properties.put("targetUserId", targetUserId);

        Map<String, Object> reason = new HashMap<>();
        reason.put("type", "string");
        reason.put("description", "禁用原因说明");
        properties.put("reason", reason);

        schema.put("properties", properties);
        schema.put("required", List.of("targetUserId"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("targetUserId");
    }

    @Override
    public ToolExecutor.ActionType getActionType() {
        return ToolExecutor.ActionType.UPDATE;
    }

    @Override
    public ToolExecutor.RiskLevel getRiskLevel() {
        return ToolExecutor.RiskLevel.HIGH;
    }

    @Override
    public boolean supportsPreview() {
        return true;
    }

    @Override
    public boolean requiresPermission() {
        return true;
    }

    /**
     * Legacy role-only callers cannot prove the permission-code contract, so they must not
     * advertise or execute this tool. The central RBAC path uses {@link #getRequiredPermissions()}.
     */
    @Override
    public boolean hasPermission(String userRole) {
        return false;
    }

    @Override
    public Set<String> getRequiredPermissions() {
        return Set.of("hr:read_write");
    }

    @Override
    public String getVersion() {
        return "3.0.0";
    }

    @Override
    public Set<String> getDomainTags() {
        return Set.of("user", "hr", "identity");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行用户禁用 - factoryId={}", factoryId);

        String reason = getString(params, "reason");
        User actor = requireCurrentHrActor(factoryId, context);
        Long targetUserId = parseTargetUserId(params);
        validateTarget(factoryId, actor, targetUserId);

        // 调用 UserService 禁用用户
        userService.deactivateUser(factoryId, targetUserId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("targetUserId", targetUserId);
        result.put("userId", targetUserId); // compatibility alias; targetUserId is authoritative
        result.put("previousStatus", "UNKNOWN");
        result.put("currentStatus", "DISABLED");
        result.put("message", "用户已禁用 (ID: " + targetUserId + ")");

        if (reason != null) {
            result.put("reason", reason);
        }
        log.info("用户禁用完成 - factoryId={}", factoryId);

        return result;
    }

    @Override
    protected Map<String, Object> doPreview(String factoryId, Map<String, Object> params,
                                             Map<String, Object> context) {
        User actor = requireCurrentHrActor(factoryId, context);
        Long targetUserId = parseTargetUserId(params);
        User target = validateTarget(factoryId, actor, targetUserId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "PREVIEW");
        result.put("success", true);
        result.put("targetUserId", targetUserId);
        result.put("currentStatus", currentStatus(target.getIsActive()));
        result.put("wouldSetActive", false);
        return result;
    }

    private Long parseTargetUserId(Map<String, Object> params) {
        Object raw = params.get("targetUserId");
        if (raw == null || (raw instanceof String s && s.isBlank())) {
            throw new IllegalArgumentException("必须提供 targetUserId；不支持 username 或操作者ID回退");
        }
        Long target = parseExactPositiveLong(raw);
        if (target == null || target <= 0) {
            throw new IllegalArgumentException("targetUserId 必须是正整数");
        }
        if (params.containsKey("username")) {
            throw new IllegalArgumentException("仅支持确认快照中的 targetUserId，不支持 username");
        }
        return target;
    }

    private Long parseExactPositiveLong(Object raw) {
        if (raw == null || raw instanceof Boolean) {
            return null;
        }
        try {
            java.math.BigDecimal decimal;
            if (raw instanceof java.math.BigDecimal value) {
                decimal = value;
            } else if (raw instanceof java.math.BigInteger value) {
                decimal = new java.math.BigDecimal(value);
            } else if (raw instanceof Byte || raw instanceof Short
                    || raw instanceof Integer || raw instanceof Long) {
                decimal = java.math.BigDecimal.valueOf(((Number) raw).longValue());
            } else if (raw instanceof Float || raw instanceof Double) {
                double value = ((Number) raw).doubleValue();
                if (!Double.isFinite(value)) {
                    return null;
                }
                decimal = java.math.BigDecimal.valueOf(value);
            } else if (raw instanceof String value && !value.isBlank()) {
                decimal = new java.math.BigDecimal(value.trim());
            } else {
                return null;
            }
            return decimal.toBigIntegerExact().longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }

    private User requireCurrentHrActor(String factoryId, Map<String, Object> context) {
        if (context == null || factoryId == null || factoryId.isBlank()) {
            throw denied("无法确认当前工厂操作者");
        }
        Long actorId = getLong(context, "userId");
        if (actorId == null) {
            throw denied("无法确认当前操作者");
        }
        User actor = userRepository.findById(actorId).orElse(null);
        try {
            if (actor == null || !Boolean.TRUE.equals(actor.getIsActive())
                    || !factoryId.equals(actor.getFactoryId())
                    || factoryConfigService == null
                    || !factoryConfigService.isModuleEnabled(factoryId, "hr_employee")
                    || userModuleAccessService == null
                    || !userModuleAccessService.canAccessModule(actor, "hr_employee")
                    || permissionService == null
                    || !permissionService.hasAnyPermission(actor, "hr:read_write")) {
                throw denied("当前账号没有启用的人事模块读写权限");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw denied("无法验证当前账号的人事模块权限");
        }
        return actor;
    }

    private User validateTarget(String factoryId, User actor, Long targetUserId) {
        if (actor.getId().equals(targetUserId)) {
            throw denied("不能停用自己的账号");
        }
        User target = userRepository.findByIdAndFactoryId(targetUserId, factoryId).orElse(null);
        if (target == null) {
            throw new BusinessException(404, "未找到当前工厂的目标用户")
                    .withHint("请确认 targetUserId 属于当前工厂且账号未删除");
        }
        if (isProtectedRole(target.getRoleCode()) || isProtectedRole(target.getPosition())) {
            throw denied("不能停用受保护的管理员账号");
        }
        return target;
    }

    private boolean isProtectedRole(String value) {
        if (value == null) {
            return false;
        }
        return Set.of("factory_super_admin", "platform_admin", "超级管理员", "平台管理员", "平台超级管理员")
                .contains(value.trim().toLowerCase(Locale.ROOT));
    }

    private String currentStatus(Boolean active) {
        if (Boolean.TRUE.equals(active)) {
            return "ACTIVE";
        }
        if (Boolean.FALSE.equals(active)) {
            return "DISABLED";
        }
        return "UNKNOWN";
    }

    private BusinessException denied(String message) {
        return new BusinessException(403, message).withHint("请使用当前工厂有效账号并确认 hr_employee 读写权限");
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "targetUserId", "请问确认要禁用哪个目标用户？请提供目标用户ID。",
            "reason", "请问禁用该用户的原因是什么？"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "targetUserId", "目标用户ID",
            "reason", "禁用原因"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
