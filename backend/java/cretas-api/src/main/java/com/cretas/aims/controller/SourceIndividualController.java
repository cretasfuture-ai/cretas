package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.source.CreateSourceIndividualRequest;
import com.cretas.aims.dto.source.SourceIndividualDTO;
import com.cretas.aims.dto.source.SourceIndividualLookupResult;
import com.cretas.aims.dto.source.UpdateSourceIndividualRequest;
import com.cretas.aims.service.source.SourceIndividualService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 源头个体档案。
 *
 * <p>路径: {@code /api/mobile/{factoryId}/source-individuals}
 *
 * <p>权限口径照 {@code MaterialBatchController}: 写要 {@code *:read_write},
 * 读则 {@code :read} 或 {@code :read_write} 任一即可 —— 个体档案是原料批次的上游,
 * 能看批次的人就该能看它来自哪个个体。
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/source-individuals")
@Tag(name = "源头个体档案", description = "溯源链最前端的个体(牛/鸭/猪…), 物种是字段值")
@Validated
@RequiredArgsConstructor
public class SourceIndividualController {

    // ⚠️ 读权限那一组在下面**逐处展开**而不是抽成常量: 注解值必须是常量表达式,
    //    引用 String[] 变量编译不过(同 FormAssistantController 里那段说明)。
    //    ⇒ 改读权限口径时四处要一起改, 这是 Java 注解的硬限制, 不是疏忽。

    private final SourceIndividualService sourceIndividualService;

    @RequirePermission({"warehouse:read", "warehouse:read_write", "inventory:read", "inventory:read_write"})
    @GetMapping
    @Operation(summary = "分页列出源头个体档案",
            description = "keyword 命中 档案编号/耳标号/物种/品种/来源场 任一")
    public ApiResponse<PageResponse<SourceIndividualDTO>> list(
            @Parameter(description = "工厂ID", required = true, example = "F006")
            @PathVariable @NotBlank String factoryId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(sourceIndividualService.list(factoryId, page, size, keyword));
    }

    /**
     * 按物理标识(耳标/脚环号)反查。
     *
     * <p>⚠️ 命中 0 条<b>不是</b> 404 —— 扫到一个还没建档的耳标是正常流程的第一步。
     * 结果里的 {@code matchCount} 把三种情况分开: 0=未建档 / 1=唯一 / &gt;1=撞号需人工选。
     */
    @RequirePermission({"warehouse:read", "warehouse:read_write", "inventory:read", "inventory:read_write"})
    @GetMapping("/by-tag/{tagNumber}")
    @Operation(summary = "按耳标/脚环号反查个体",
            description = "耳标号不唯一(脱落重挂/跨场撞号), 返回全部命中 + 条数")
    public ApiResponse<SourceIndividualLookupResult> findByTag(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String tagNumber) {
        return ApiResponse.success(sourceIndividualService.findByTagNumber(factoryId, tagNumber));
    }

    @RequirePermission({"warehouse:read", "warehouse:read_write", "inventory:read", "inventory:read_write"})
    @GetMapping("/{id}")
    @Operation(summary = "获取源头个体档案详情")
    public ApiResponse<SourceIndividualDTO> getById(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String id) {
        return ApiResponse.success(sourceIndividualService.getById(factoryId, id));
    }

    @RequirePermission({"warehouse:read_write", "inventory:read_write"})
    @RequireModule("warehouse")
    @PostMapping
    @Operation(summary = "建立源头个体档案")
    @com.cretas.aims.annotation.Loggable(module = "SOURCE_INDIVIDUAL", action = "CREATE",
            entityType = "SourceIndividual")
    public ApiResponse<SourceIndividualDTO> create(
            @PathVariable @NotBlank String factoryId,
            @Valid @RequestBody CreateSourceIndividualRequest request) {
        log.info("建立源头个体档案: factoryId={}, individualCode={}", factoryId, request.getIndividualCode());
        return ApiResponse.success("建档成功", sourceIndividualService.create(factoryId, request));
    }

    @RequirePermission({"warehouse:read_write", "inventory:read_write"})
    @RequireModule("warehouse")
    @PutMapping("/{id}")
    @Operation(summary = "更新源头个体档案 (partial body: null = 不修改)")
    @com.cretas.aims.annotation.Loggable(module = "SOURCE_INDIVIDUAL", action = "UPDATE",
            entityType = "SourceIndividual", entityIdParam = "id")
    public ApiResponse<SourceIndividualDTO> update(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String id,
            @Valid @RequestBody UpdateSourceIndividualRequest request) {
        return ApiResponse.success("更新成功", sourceIndividualService.update(factoryId, id, request));
    }

    @RequirePermission({"warehouse:read_write", "inventory:read_write"})
    @RequireModule("warehouse")
    @DeleteMapping("/{id}")
    @Operation(summary = "删除源头个体档案 (软删)")
    @com.cretas.aims.annotation.Loggable(module = "SOURCE_INDIVIDUAL", action = "DELETE",
            entityType = "SourceIndividual", entityIdParam = "id")
    public ApiResponse<Void> delete(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String id) {
        sourceIndividualService.softDelete(factoryId, id);
        return ApiResponse.successMessage("删除成功");
    }
}
