package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.decision.ProposalComparisonRequest;
import com.cretas.aims.dto.decision.ProposalComparisonResult;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.ProposalComparisonService;
import com.cretas.aims.utils.FactoryAccessValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mobile/{factoryId}/proposal-comparisons")
@RequireRole({"factory_super_admin", "permission_admin"})
@RequirePermission({"system:read"})
public class ProposalComparisonController {
    static final int MAX_JSON_BYTES = 64 * 1024;
    private final FactoryAccessValidator factoryAccess;
    private final ProposalComparisonService comparison;
    private final ObjectMapper mapper;

    @PostMapping(value = "/evaluate", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "对照同口径候选方案的成本、交期与资源取舍",
            description = "请求最多64KiB，最多20候选。仅计算调用方未验证事实和同一资源池共同窗口的总量预算；窗口[start,end]双端包含。最小化成本、完成时间和资源需求，返回非支配集合而非排序或赢家。缺失/混合口径不生成集合；不查业务数据、不执行方案或预留资源，POST不持久化业务数据。",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ProposalComparisonRequest.class))))
    public ApiResponse<ProposalComparisonResult> evaluate(@PathVariable String factoryId,
                                                         HttpServletRequest request) {
        factoryAccess.validateAccess(factoryId);
        // No @RequestBody binding: authorization and the byte budget must precede Jackson.
        // The stream bound is mandatory even when Content-Length is absent or untrusted.
        if (request.getContentLengthLong() > MAX_JSON_BYTES) throw tooLarge();
        final byte[] bytes;
        try {
            bytes = request.getInputStream().readNBytes(MAX_JSON_BYTES + 1);
        } catch (IOException unreadable) {
            throw new BusinessException(400, "无法读取比较数据，请重新提交JSON请求");
        }
        if (bytes.length > MAX_JSON_BYTES) throw tooLarge();
        try {
            ProposalComparisonRequest facts = mapper.readerFor(ProposalComparisonRequest.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(bytes);
            return ApiResponse.success(comparison.compare(facts));
        } catch (IOException malformed) {
            throw new BusinessException(400, "比较数据格式无效，请提交一个完整的JSON对象");
        }
    }

    private static ResponseStatusException tooLarge() {
        return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "比较数据不能超过64KiB，请减少候选或数据长度");
    }
}
