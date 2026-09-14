package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.vision.VisionVerifyRequest;
import com.cretas.aims.dto.vision.VisionVerifyResponse;
import com.cretas.aims.service.vision.VisionResponseUnparseableException;
import com.cretas.aims.service.vision.VisionTemplateRegistry;
import com.cretas.aims.service.vision.VisionVerifyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 拍照核验 —— 按代码内模板拍一张照片, 出结构化字段。
 *
 * <p>路径: {@code /api/mobile/{factoryId}/vision}
 *
 * <h3>为什么 422 是一条正路而不是异常路径</h3>
 * 「模型没看懂这张照片」是<b>现场每天都会发生</b>的事(手抖、反光、耳标被泥盖住)。
 * 它必须与「服务挂了」(502)、「模板名写错了」(400)分开, 因为用户的下一步动作不同:
 * 422 ⇒ 重拍; 502 ⇒ 等一会儿/找运维; 400 ⇒ 客户端有 bug。
 * ⇒ 422 的 body 里带 {@code rawText}, 让现场看得到模型到底说了什么。
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/vision")
@Tag(name = "拍照核验", description = "按模板拍照 → 结构化字段 → 模板后处理")
@Validated
@RequiredArgsConstructor
public class VisionVerifyController {

    private final VisionVerifyService visionVerifyService;

    /**
     * 有哪些模板 —— 前端的 chip 列表从这里来, ⛔ 不在前端再抄一份模板清单(形态 D)。
     */
    @RequirePermission({"warehouse:read", "warehouse:read_write", "inventory:read", "inventory:read_write"})
    @GetMapping("/templates")
    @Operation(summary = "列出可用的核验模板")
    public ApiResponse<List<Map<String, Object>>> templates(
            @PathVariable @NotBlank String factoryId) {
        return ApiResponse.success(VisionTemplateRegistry.all().stream()
                .map(t -> Map.<String, Object>of(
                        "code", t.code(),
                        "displayName", t.displayName(),
                        "requiredKeys", t.requiredKeys().stream().sorted().toList()))
                .toList());
    }

    @RequirePermission({"warehouse:read_write", "inventory:read_write"})
    @PostMapping("/verify")
    @Operation(summary = "拍照核验",
            description = "EAR_TAG: 读耳标号并反查个体档案; MEAT_CUT: 肉品外观初评(固定 AI_PRELIMINARY)")
    public ResponseEntity<ApiResponse<VisionVerifyResponse>> verify(
            @Parameter(description = "工厂ID", required = true, example = "F006")
            @PathVariable @NotBlank String factoryId,
            @Valid @RequestBody VisionVerifyRequest request) {

        log.info("拍照核验: factoryId={}, template={}, imageChars={}", factoryId,
                request.getTemplateCode(),
                request.getImageBase64() == null ? 0 : request.getImageBase64().length());
        try {
            VisionVerifyResponse result = visionVerifyService.verify(factoryId, request);
            return ResponseEntity.ok(ApiResponse.success("识别成功", result));
        } catch (VisionResponseUnparseableException e) {
            // ⛔ 这里**只**回 templateCode / rawText / parseError 三样。
            //    不塞 fields(哪怕是空 map)、不塞 confidence —— 「没解析出来」不是
            //    「解析出来是空的」, 前端必须能把这两种区分开。
            log.warn("拍照核验解析失败: factoryId={}, template={}, reason={}",
                    factoryId, e.getTemplateCode(), e.getMessage());
            VisionVerifyResponse body = VisionVerifyResponse.builder()
                    .templateCode(e.getTemplateCode())
                    .rawText(e.getRawText())
                    .parseError(e.getMessage())
                    .build();
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(ApiResponse.of(422, e.getMessage(), body));
        }
    }
}
