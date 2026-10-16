package com.cretas.aims.controller.inventory;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.enums.ExceptionDecision;
import com.cretas.aims.entity.inventory.PurchaseException;
import com.cretas.aims.service.inventory.PurchaseExceptionService;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.exception.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

/**
 * 采购异常单 REST 控制器（SP6）
 *
 * <p>异常单由 confirmReceive 自动创建（超收/少收），采购员事后做 ACCEPT/RETURN 决策。
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/purchase-exceptions")
@RequiredArgsConstructor
@Tag(name = "采购异常管理", description = "SP6 采购超收/少收异常单管理")
@RequirePermission({"procurement:read_write", "procurement:read"})
public class PurchaseExceptionController {

    private final PurchaseExceptionService purchaseExceptionService;

    // ─── 查询异常单列表 ────────────────────────────────────────────────────────

    @GetMapping
    @Operation(summary = "查询采购异常单列表", description = "status 不传时返回全部，可传 PENDING/RESOLVED 过滤")
    public ApiResponse<List<PurchaseException>> listExceptions(
            @PathVariable @NotBlank String factoryId,
            @RequestParam(required = false) String status) {
        List<PurchaseException> list = purchaseExceptionService.listExceptions(factoryId, status);
        return ApiResponse.success("查询成功", list);
    }

    // ─── 异常单决策 ────────────────────────────────────────────────────────────

    @PostMapping("/{exceptionId}/decide")
    @Operation(summary = "对采购异常单做决策",
            description = "decision: ACCEPT_OVER（接受超收）/ RETURN_OVER（退回超收）/ ACCEPT_SHORT（接受少收）/ REQUEST_RESUPPLY（补采购）。"
                    + " D-6：仅责任采购人本人或主管角色（factory_super_admin / procurement_manager）可决策。")
    @RequirePermission({"procurement:read_write"})
    public ApiResponse<PurchaseException> decideException(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String exceptionId,
            @RequestHeader("Authorization") String authorization,
            HttpServletRequest request,
            @RequestBody Map<String, String> body) {
        AuthenticatedActor actor = AuthenticatedActor.current(request)
                .orElseThrow(() -> new BusinessException(401, "请重新登录后处理采购异常"));
        // 责任人及退货 createdBy 属于工厂 users 命名空间，平台账号不能凭同数字 ID 代办。
        if (actor.type() != AuthenticatedActorType.FACTORY_USER || !factoryId.equals(actor.factoryId())) {
            throw new BusinessException(403, "采购异常决策须由本工厂责任采购人或主管账号处理");
        }
        Long userId = actor.id();
        String callerRole = actor.role();
        String decisionStr = body.get("decision");
        String notes = body.get("notes");

        ExceptionDecision decision;
        try {
            decision = ExceptionDecision.valueOf(decisionStr);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new com.cretas.aims.exception.BusinessException(400,
                    "无效的决策类型: " + decisionStr + "，可选值: ACCEPT_OVER / RETURN_OVER / ACCEPT_SHORT / REQUEST_RESUPPLY");
        }

        log.info("[SP6] 异常单决策: factoryId={}, exceptionId={}, decision={}, userId={}, role={}",
                factoryId, exceptionId, decision, userId, callerRole);
        PurchaseException updated = purchaseExceptionService.decideException(
                exceptionId, factoryId, decision, notes, userId, callerRole);
        return ApiResponse.success("决策已记录", updated);
    }

}
