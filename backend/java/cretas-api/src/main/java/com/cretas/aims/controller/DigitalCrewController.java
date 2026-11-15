package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.ai.IntentConfirmationRequest;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.service.crew.DigitalCrewService;
import com.cretas.aims.service.crew.DigitalCrewService.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 迁移 P8「数字班组」接口 (MIGRATION-PLAN 2.7)。
 *
 * <p>⛔ 操作人只从会话 ({@link AuthenticatedActor}) 取: 类型 / id / 角色都来自 JWT, 请求体里没有任何身份或权限字段。
 * 方法上的 {@code @RequirePermission} 是入口粗筛 (写的是数字班组覆盖的模块并集, 注解只能写字面量,
 * 改一处要改同组几处); 每件事的确认权限在 {@link DigitalCrewService} 里按事项声明的权限码逐件校验。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mobile/{factoryId}/digital-crew")
@Tag(name = "数字班组", description = "数字员工启用配置 / 对话 / 等我确认 / 工作记录与额度")
public class DigitalCrewController {

    private final DigitalCrewService service;

    public record AskRequest(String text, String sessionId) {
    }

    public record MembersRequest(Map<String, Boolean> enabled, String reason) {
    }

    public record RoleMappingRequest(Map<String, String> mapping, String reason) {
    }

    public record RejectRequest(String reason) {
    }

    @RequirePermission({"dashboard:read", "sales:read", "procurement:read", "warehouse:read", "inventory:read",
            "production:read", "scheduling:read", "quality:read", "finance:read", "analytics:read", "work_report:read"})
    @GetMapping("/members")
    @Operation(summary = "五位数字员工", description = "本厂启用与否 + 当前登录人在各模块的权限级别")
    public ApiResponse<List<DigitalCrewService.MemberView>> members(@PathVariable String factoryId, HttpServletRequest http) {
        return ApiResponse.success(service.members(factoryId, actor(http, factoryId)));
    }

    @RequirePermission({"dashboard:read", "sales:read", "procurement:read", "warehouse:read", "inventory:read",
            "production:read", "scheduling:read", "quality:read", "finance:read", "analytics:read", "work_report:read"})
    @GetMapping("/settings")
    @Operation(summary = "数字班组配置", description = "启用哪几位、一线岗位对应、调整记录")
    public ApiResponse<DigitalCrewService.SettingsView> settings(@PathVariable String factoryId, HttpServletRequest http) {
        return ApiResponse.success(service.settings(factoryId, actor(http, factoryId)));
    }

    @RequirePermission({"system:read_write"})
    @PutMapping("/settings/members")
    @Operation(summary = "改本厂启用哪几位", description = "必须写原因; 留调整记录")
    public ApiResponse<DigitalCrewService.SettingsView> updateMembers(@PathVariable String factoryId,
                                                                      @RequestBody MembersRequest body,
                                                                      HttpServletRequest http) {
        return ApiResponse.success("已保存",
                service.updateMembers(factoryId, actor(http, factoryId), body.enabled(), body.reason()));
    }

    @RequirePermission({"system:read_write"})
    @PutMapping("/settings/role-mapping")
    @Operation(summary = "改一线岗位 → 数字员工", description = "必须写原因; 留调整记录")
    public ApiResponse<DigitalCrewService.SettingsView> updateRoleMapping(@PathVariable String factoryId,
                                                                          @RequestBody RoleMappingRequest body,
                                                                          HttpServletRequest http) {
        return ApiResponse.success("已保存",
                service.updateRoleMapping(factoryId, actor(http, factoryId), body.mapping(), body.reason()));
    }

    @RequirePermission({"dashboard:read", "sales:read", "procurement:read", "warehouse:read", "inventory:read",
            "production:read", "scheduling:read", "quality:read", "finance:read", "analytics:read", "work_report:read"})
    @PostMapping("/members/{memberKey}/ask")
    @Operation(summary = "对一位数字员工说一句话", description = "查询直接返回; 写操作只记「已交办」进等我确认, 不执行")
    public ApiResponse<DigitalCrewService.AskView> ask(@PathVariable String factoryId, @PathVariable String memberKey,
                                                       @RequestBody AskRequest body, HttpServletRequest http) {
        return ApiResponse.success(service.ask(factoryId, actor(http, factoryId), memberKey,
                body == null ? null : body.text(), body == null ? null : body.sessionId()));
    }

    @RequirePermission({"dashboard:read", "sales:read", "procurement:read", "warehouse:read", "inventory:read",
            "production:read", "scheduling:read", "quality:read", "finance:read", "analytics:read", "work_report:read"})
    @GetMapping("/inbox")
    @Operation(summary = "等我确认", description = "已交办、我有权确认的事; 另报按权限不显示的件数")
    public ApiResponse<DigitalCrewService.InboxView> inbox(@PathVariable String factoryId, HttpServletRequest http) {
        return ApiResponse.success(service.inbox(factoryId, actor(http, factoryId)));
    }

    @RequirePermission({"sales:read_write", "procurement:read_write", "warehouse:read_write", "inventory:read_write",
            "production:read_write", "scheduling:read_write", "work_report:read_write", "quality:read_write",
            "finance:read_write", "analytics:read_write"})
    @PostMapping("/items/{id}/preview")
    @Operation(summary = "按我的身份预览", description = "走 AI 写入预览; 凭据只回给我, 本表只记摘要")
    public ApiResponse<DigitalCrewService.PreviewView> preview(@PathVariable String factoryId, @PathVariable Long id,
                                                               HttpServletRequest http) {
        return ApiResponse.success(service.preview(factoryId, actor(http, factoryId), id));
    }

    @RequirePermission({"sales:read_write", "procurement:read_write", "warehouse:read_write", "inventory:read_write",
            "production:read_write", "scheduling:read_write", "work_report:read_write", "quality:read_write",
            "finance:read_write", "analytics:read_write"})
    @PostMapping("/items/{id}/confirm")
    @Operation(summary = "确认执行", description = "服务端按登录人校验该事项的写权限; 凭据从专用请求头传")
    public ApiResponse<DigitalCrewService.ItemView> confirm(@PathVariable String factoryId, @PathVariable Long id,
                                                            @RequestHeader("X-Cretas-Confirmation-Token") String proofToken,
                                                            @Valid @RequestBody IntentConfirmationRequest body,
                                                            HttpServletRequest http) {
        if (proofToken == null || proofToken.isBlank() || proofToken.length() > 2048) {
            throw new BusinessException(400, "确认凭据缺失或不合法，请重新预览");
        }
        DigitalCrewService.ItemView v = service.confirm(factoryId, actor(http, factoryId), id,
                body.toConfirmationProof(proofToken));
        return ApiResponse.success(v.statusLabel(), v);
    }

    @RequirePermission({"dashboard:read", "sales:read", "procurement:read", "warehouse:read", "inventory:read",
            "production:read", "scheduling:read", "quality:read", "finance:read", "analytics:read", "work_report:read"})
    @PostMapping("/items/{id}/reject")
    @Operation(summary = "不办", description = "记没办成, 不扣额度; 有权确认的人或交办人本人可操作; 必须写原因")
    public ApiResponse<DigitalCrewService.ItemView> reject(@PathVariable String factoryId, @PathVariable Long id,
                                                           @RequestBody RejectRequest body, HttpServletRequest http) {
        return ApiResponse.success("已记为没办成",
                service.reject(factoryId, actor(http, factoryId), id, body == null ? null : body.reason()));
    }

    @RequirePermission({"dashboard:read", "sales:read", "procurement:read", "warehouse:read", "inventory:read",
            "production:read", "scheduling:read", "quality:read", "finance:read", "analytics:read", "work_report:read"})
    @GetMapping("/ledger")
    @Operation(summary = "工作记录与额度", description = "四态分开计数; 只有已办成扣额度; 按真实日期")
    public ApiResponse<DigitalCrewService.LedgerView> ledger(@PathVariable String factoryId,
                                                             @RequestParam(required = false) String member,
                                                             HttpServletRequest http) {
        return ApiResponse.success(service.ledger(factoryId, actor(http, factoryId), member));
    }

    static Actor actor(HttpServletRequest request, String factoryId) {
        AuthenticatedActor a = AuthenticatedActor.current(request)
                .orElseThrow(() -> new BusinessException(401, "无法识别当前操作人"));
        if (a.isPlatformAdmin()) {
            if (!a.hasFactoryBypass()) throw new BusinessException(403, "当前平台角色不能查看工厂的数字班组");
        } else if (a.factoryId() == null || !a.factoryId().equals(factoryId)) {
            throw new BusinessException(403, "登录账号不属于该工厂");
        }
        return new Actor(a.type(), a.id(), a.role());
    }
}
