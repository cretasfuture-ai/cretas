package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.service.smartbi.SmartBIUploadTemplates;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** SmartBI 上传模板下载（原型 fusion-data-templates）。不依赖数据库。 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/smart-bi")
@Tag(name = "SmartBI Upload", description = "SmartBI Excel upload and data management API")
public class SmartBIUploadTemplateController {

    @RequirePermission({"analytics:read"})
    @GetMapping("/upload-template")
    @Operation(summary = "下载数据上传模板", description = "type: sales/finance/inventory/customer/product；其他值返回 400")
    public ResponseEntity<byte[]> downloadTemplate(@PathVariable String factoryId, @RequestParam String type) {
        byte[] data = SmartBIUploadTemplates.build(type);
        String name = URLEncoder.encode(SmartBIUploadTemplates.definition(type).sheetName() + "模板.xlsx",
                StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"data-template.xlsx\"; filename*=UTF-8''" + name)
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(data.length)
                .body(data);
    }
}
