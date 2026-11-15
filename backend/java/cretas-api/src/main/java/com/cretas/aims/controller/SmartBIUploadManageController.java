package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.smartbi.postgres.SmartBiPgExcelUpload;
import com.cretas.aims.service.smartbi.SmartBIUploadManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** SmartBI 上传记录改名 / 真删除（原型 fusion-upload 的 rename 与 DELETE）。 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/smart-bi")
@Tag(name = "SmartBI Upload", description = "SmartBI Excel upload and data management API")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "smartbi.postgres.enabled", havingValue = "true", matchIfMissing = false)
public class SmartBIUploadManageController {

    private final SmartBIUploadManagementService service;

    @RequirePermission({"analytics:read_write"})
    @RequestMapping(value = "/uploads/{uploadId}/rename",
            method = {RequestMethod.PATCH, RequestMethod.PUT, RequestMethod.POST})
    @Operation(summary = "上传记录改名", description = "body: {fileName}")
    public ApiResponse<Map<String, Object>> rename(@PathVariable String factoryId, @PathVariable Long uploadId,
                                                   @RequestBody Map<String, String> body) {
        SmartBiPgExcelUpload u = service.rename(factoryId, uploadId, body == null ? null : body.get("fileName"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", u.getId());
        data.put("fileName", u.getFileName());
        return ApiResponse.success("已改名", data);
    }

    @RequirePermission({"analytics:read_write"})
    @DeleteMapping("/uploads/{uploadId}")
    @Operation(summary = "删除上传记录（物理删除，含数据行/字段/分析结果）")
    public ApiResponse<Void> delete(@PathVariable String factoryId, @PathVariable Long uploadId) {
        service.delete(factoryId, uploadId);
        return ApiResponse.successMessage("已删除");
    }
}
