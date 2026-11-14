package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.issue.IssueDisposition;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.issue.IssueDispositionService;
import com.cretas.aims.service.issue.IssueDispositionService.SourceRef;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 迁移 P4「发现的问题」(/issues) 的处置接口: 关闭 (选结论 + 写一句) / 转成我的待办 / 恢复。
 *
 * <p>问题本身由前端从各来源已有接口读 (各按自己的权限); 这里只存处置, ⛔ 不改来源单据。
 * 写入的来源归属与处理权限在 {@link IssueDispositionService} 里逐条校验。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mobile/{factoryId}/issues/dispositions")
@Tag(name = "发现的问题", description = "问题处置: 关闭结论 / 转待办 / 恢复")
public class IssueDispositionController {

    // 读 / 写入口: 能看到任一来源的人都能进 (处置标记只有标题快照与结论, 不含来源明细);
    // 写入时 IssueDispositionService 再按来源逐条查本厂归属与处理权限 (read_write)。
    // 注解里只能写字面量数组, 四处相同, 改一处要改四处。

    private final IssueDispositionService service;

    public record DispositionView(String sourceType, String sourceId, String status, String conclusion,
                                  String conclusionLabel, String reason, String title, String linkPath,
                                  Long assigneeUserId, String actorType, Long actorId, LocalDateTime actedAt) {
        static DispositionView of(IssueDisposition d) {
            return new DispositionView(d.getSourceType().name(), d.getSourceId(), d.getStatus().name(),
                    d.getConclusion() == null ? null : d.getConclusion().name(),
                    d.getConclusion() == null ? null : d.getConclusion().label(),
                    d.getReason(), d.getTitle(), d.getLinkPath(), d.getAssigneeUserId(),
                    d.getActorType(), d.getActorId(), d.getActedAt());
        }
    }

    public record CloseRequest(String sourceType, String sourceId, String title, String linkPath,
                               String conclusion, String reason) {
    }

    public record RefRequest(String sourceType, String sourceId, String title, String linkPath) {
    }

    @RequirePermission({"production:read", "production:read_write", "sales:read", "sales:read_write",
            "procurement:read", "procurement:read_write", "warehouse:read", "warehouse:read_write",
            "quality:read", "quality:read_write", "finance:read", "finance:read_write"})
    @GetMapping
    @Operation(summary = "本厂已关闭 / 已转待办的问题处置", description = "最近 500 条; 只返回操作人有读权限的来源类型")
    public ApiResponse<List<DispositionView>> list(@PathVariable String factoryId, HttpServletRequest http) {
        ActorRef actor = ShortageCaseActionController.actor(http, factoryId);
        return ApiResponse.success("获取成功", service.listActive(factoryId, actor).stream().map(DispositionView::of).toList());
    }

    @GetMapping("/assigned-to-me")
    @Operation(summary = "转给我的待办", description = "「待办 › 指派给我」的问题来源; 只返回本人的")
    public ApiResponse<List<DispositionView>> assignedToMe(@PathVariable String factoryId, HttpServletRequest http) {
        ActorRef actor = ShortageCaseActionController.actor(http, factoryId);
        if (actor.type() != AuthenticatedActorType.FACTORY_USER) {
            return ApiResponse.success("平台账号没有工厂待办", List.of());
        }
        return ApiResponse.success("获取成功",
                service.assignedTo(factoryId, actor).stream().map(DispositionView::of).toList());
    }

    @RequirePermission({"production:read", "production:read_write", "sales:read", "sales:read_write",
            "procurement:read", "procurement:read_write", "warehouse:read", "warehouse:read_write",
            "quality:read", "quality:read_write", "finance:read", "finance:read_write"})
    @PostMapping("/close")
    @Operation(summary = "关闭问题", description = "必须选结论 (HANDLED 已处理 / FALSE_ALARM 误报 / ACCEPTED_DEVIATION 接受偏差) 并写一句说明")
    public ApiResponse<DispositionView> close(@PathVariable String factoryId, @RequestBody CloseRequest body,
                                              HttpServletRequest http) {
        ActorRef actor = ShortageCaseActionController.actor(http, factoryId);
        IssueDisposition d = service.close(factoryId, actor,
                new SourceRef(body.sourceType(), body.sourceId(), body.title(), body.linkPath()),
                body.conclusion(), body.reason());
        return ApiResponse.success("已关闭", DispositionView.of(d));
    }

    @RequirePermission({"production:read", "production:read_write", "sales:read", "sales:read_write",
            "procurement:read", "procurement:read_write", "warehouse:read", "warehouse:read_write",
            "quality:read", "quality:read_write", "finance:read", "finance:read_write"})
    @PostMapping("/convert")
    @Operation(summary = "转成我的待办", description = "指派给本人, 出现在「待办 › 指派给我」")
    public ApiResponse<DispositionView> convert(@PathVariable String factoryId, @RequestBody RefRequest body,
                                                HttpServletRequest http) {
        ActorRef actor = ShortageCaseActionController.actor(http, factoryId);
        IssueDisposition d = service.convertToTodo(factoryId, actor,
                new SourceRef(body.sourceType(), body.sourceId(), body.title(), body.linkPath()));
        return ApiResponse.success("已转成待办", DispositionView.of(d));
    }

    @RequirePermission({"production:read", "production:read_write", "sales:read", "sales:read_write",
            "procurement:read", "procurement:read_write", "warehouse:read", "warehouse:read_write",
            "quality:read", "quality:read_write", "finance:read", "finance:read_write"})
    @PostMapping("/reopen")
    @Operation(summary = "恢复问题", description = "回到未处置, 清空结论与指派")
    public ApiResponse<DispositionView> reopen(@PathVariable String factoryId, @RequestBody RefRequest body,
                                               HttpServletRequest http) {
        ActorRef actor = ShortageCaseActionController.actor(http, factoryId);
        IssueDisposition d = service.reopen(factoryId, actor,
                new SourceRef(body.sourceType(), body.sourceId(), body.title(), body.linkPath()));
        return ApiResponse.success("已恢复", DispositionView.of(d));
    }
}
