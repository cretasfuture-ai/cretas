package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.finding.FindingService;
import com.cretas.aims.service.finding.FindingTextRenderer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 发现层的**主动出口**：让页面在用户没提问的情况下就能拿到「今天该知道的事」。
 *
 * <p>供工厂库存/仓储驾驶舱读取主动发现，默认领域为 inventory。
 *
 * <p>⛔ 三个桶全部原样透出，不在这里合并：前端必须能区分
 * 「都正常」({@code checkedRules} 非空 + {@code findings} 空)、
 * 「判不了」({@code skippedRules})、
 * 「查询失败」({@code failedRules} / {@code complete=false})。
 * 合并成一个 boolean 就等于把三种话压成一种。
 */
@Slf4j
@Tag(name = "发现层", description = "主动发现（顺带提示 / 驾驶舱卡片共用同一口径）")
@RestController
@RequestMapping("/api/mobile/{factoryId}/findings")
@RequiredArgsConstructor
public class FindingController {

    private static final String DEFAULT_DOMAIN = "inventory";

    private final FindingService findingService;
    private final FindingTextRenderer findingTextRenderer;
    private final com.cretas.aims.service.finding.FindingActionPlanService findingActionPlanService;

    @GetMapping
    @RequirePermission({"inventory:read", "inventory:read_write"})
    @Operation(summary = "查询当前工厂的发现", description = "供驾驶舱/日报等主动出口使用")
    public ApiResponse<Map<String, Object>> getFindings(
            @PathVariable String factoryId,
            @RequestParam(required = false) String domain) {

        String resolvedDomain = resolveDomain(domain);
        FindingService.Result result = findingService.detectInline(factoryId, resolvedDomain);
        String findingsText = findingTextRenderer.renderInline(result);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("domain", resolvedDomain);
        data.put("findings", result.findings());
        data.put("findingsText", findingsText);
        // 🔴 前端渲染卡片用这个成品数组, 不要拿 findings[].facts 自己拼:
        // PriceFieldResponseAdvice 会把 facts.cost / facts.totalCost 置 null
        // (FINANCE_COLUMN_KEY_REGEX 对含 "cost" 的数字标量一律抹除, 本是给
        // SmartBI Excel 财务表用的, 对餐饮损耗金额是误伤), 前端自己拼会出现空的「¥ 」。
        data.put("digestLines", findingTextRenderer.renderDigestLines(result));
        data.put("totalCount", result.totalCount());
        data.put("checkedRules", result.checkedRules());
        data.put("skippedRules", result.skippedRules());
        data.put("failedRules", result.failedRules());
        data.put("complete", result.complete());
        log.info("发现层查询: factoryId={}, domain={}, findings={}, checked={}, skipped={}, failed={}",
                factoryId, resolvedDomain, result.findings().size(), result.checkedRules().size(),
                result.skippedRules().size(), result.failedRules().size());
        return ApiResponse.success(data);
    }

    @GetMapping("/action-plan")
    @RequirePermission({"inventory:read", "inventory:read_write"})
    @Operation(summary = "把当前发现整理成行动建议", description = "数字全部来自发现层，LLM 只负责措辞")
    public ApiResponse<Map<String, Object>> getActionPlan(
            @PathVariable String factoryId,
            @RequestParam(required = false) String domain) {
        String resolvedDomain = resolveDomain(domain);
        return ApiResponse.success(findingActionPlanService.generate(factoryId, resolvedDomain));
    }

    private String resolveDomain(String requested) {
        String resolved = (requested == null || requested.isBlank()) ? DEFAULT_DOMAIN : requested;
        if (!DEFAULT_DOMAIN.equals(resolved)) {
            throw new BusinessException(400, "发现层仅支持工厂 inventory 领域");
        }
        return resolved;
    }

}
