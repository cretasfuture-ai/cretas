package com.cretas.aims.controller.asyncops;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.service.asyncops.AsyncTaskFailureLedger;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * {@code @Async} 失败台账的人工出口 —— 看明细 + 处理完之后让告警闭嘴。
 *
 * <h3>为什么必须有「标记已处理」</h3>
 * <p>本仓形态 E: <b>一道会误报、或者永远静不下来的闸, 最终会被加 noqa、注掉、
 * 或者被人默认忽略, 那时它的覆盖率归零。</b>
 * 定时跑批量的是<b>存量</b> (还有多少条没人处理), 如果运维补完账没法把它划掉,
 * 那条 WARN 会天天出现且数字只增不减 —— 第三天就没人看了, 整个出口白建。
 *
 * <p>⛔ 所以「resolve」是这套东西的一部分, 不是可选的锦上添花。
 *
 * <h3>刻意没有做的事</h3>
 * <p><b>没有「一键重试」按钮。</b> 补偿入口<b>已经有了</b>, 而且是对的:
 * {@code POST /api/mobile/{factoryId}/vouchers/generate} →
 * {@code VoucherServiceImpl.batchCreateForFactory} —— 每单独立 {@code REQUIRES_NEW} 事务,
 * {@code createFromBusiness} 幂等, 且 {@code isRetryableFlag} 已经收
 * {@code UNCREATED / FAILED / PENDING} 三种 flag (2026-08-10 起), 正好覆盖卡住的那批。
 * 再写一个通用重试引擎就是同一件事有两份, 它一定会漂 (本仓形态 D)。
 * ⇒ 台账只负责<b>把人指到已有的那个入口</b>, 每条记录带着 {@code remediationHint}。
 *
 * <p>⚠️ 而且三个已登记方法里只有凭证那条有安全的自动补偿。供应链联动那条重放会
 * <b>重复预留库存</b>, 缺料报告那条是只读派生物 —— 三条的正确动作各不相同,
 * 一个统一的「重试」按钮只会把它们混为一谈。
 */
@Validated
@RestController
@RequestMapping("/api/mobile/{factoryId}/async-failures")
@RequirePermission({"system:read_write"})
@RequireRole({"factory_super_admin", "platform_admin", "super_admin", "developer", "platform_super_admin"})
@RequiredArgsConstructor
public class AsyncTaskFailureController {

    private final AsyncTaskFailureLedger ledger;

    /**
     * 列出本工厂的异步失败。
     *
     * <p>⚠️ 看不到 {@code factory_id IS NULL} 的行 —— 那些是提取器判不出工厂的失败,
     * 只有平台侧全量查询够得着。这是刻意的: 按工厂过滤就不能顺手把归属不明的也塞进来,
     * 否则 A 厂会看到可能属于 B 厂的失败。
     */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
            @PathVariable String factoryId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit,
            HttpServletRequest servletRequest) {
        assertFactoryScope(factoryId, servletRequest);
        return ApiResponse.success(ledger.list(factoryId, status, limit));
    }

    /**
     * 标记一条为已处理。
     *
     * <p>{@code resolve} 内部带 {@code factory_id} 条件, 所以越权请求更新 0 行 ——
     * ⛔ 这里不能改成「先更新再检查工厂」: 本仓已有实测缺陷
     * ({@code CanvasAlertController.acknowledgeEvent}) 就是先改后查,
     * 结果跨工厂请求拿到 403 但那一行<b>已经被改掉并提交了</b>。
     */
    @PutMapping("/{id}/resolve")
    public ApiResponse<Map<String, Object>> resolve(
            @PathVariable String factoryId,
            @PathVariable long id,
            @RequestBody(required = false) ResolveRequest request,
            HttpServletRequest servletRequest) {
        assertFactoryScope(factoryId, servletRequest);
        String operator = String.valueOf(servletRequest.getAttribute("userId"));
        String note = request == null ? null : request.note();

        int updated = ledger.resolve(id, factoryId, operator, note);
        if (updated == 0) {
            // 0 行有三种可能: id 不存在 / 不属于本厂 / 已经是 RESOLVED。
            // ⛔ 不区分是刻意的 —— 区分开就是一个跨工厂探测 id 是否存在的口子。
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "没有可处理的记录 (不存在、不属于本工厂, 或已处理)");
        }
        return ApiResponse.success("已标记为处理完成", Map.of("id", id, "updated", updated));
    }

    /** @param note 处理说明, 可空 */
    public record ResolveRequest(String note) {
    }

    /**
     * 身份只认 JWT 填进来的 request attribute, ⛔ 不认路径里的 factoryId。
     * 与 {@code LabelQcController.trustedUser} 同一套判据。
     */
    private void assertFactoryScope(String pathFactoryId, HttpServletRequest request) {
        Object trustedFactory = request.getAttribute("factoryId");
        if (trustedFactory != null && !pathFactoryId.equals(String.valueOf(trustedFactory))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "factory scope mismatch");
        }
    }
}
