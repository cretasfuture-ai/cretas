package com.cretas.aims.controller;

import com.cretas.aims.dto.smartbi.UploadHistoryDTO;
import com.cretas.aims.entity.smartbi.enums.UploadStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 味美 11-19 (B2): GET /uploads?status=COMPLETED 之前整个忽略 status,
 * 保存失败的上传 (FAILED) 也进了 AI 问数据的数据源下拉。
 */
class SmartBIUploadHistoryStatusFilterContractTest {

    private static UploadHistoryDTO dto(long id, UploadStatus status) {
        return new UploadHistoryDTO(id, "f" + id + ".xlsx", "s", "GENERAL", 10, 3, status, LocalDateTime.now());
    }

    private static Page<UploadHistoryDTO> page() {
        return new PageImpl<>(List.of(
                dto(5331, UploadStatus.FAILED),
                dto(5335, UploadStatus.COMPLETED),
                dto(5320, UploadStatus.COMPLETED)), PageRequest.of(0, 200), 3);
    }

    @Test
    void completedFilterDropsFailedUploads() {
        Page<UploadHistoryDTO> out = SmartBIUploadController.filterUploadHistoryByStatus(page(), "COMPLETED");
        assertThat(out.getContent()).extracting(UploadHistoryDTO::getId).containsExactly(5335L, 5320L);
    }

    @Test
    void noStatusKeepsEverythingForTheUploadHistoryPage() {
        assertThat(SmartBIUploadController.filterUploadHistoryByStatus(page(), null).getContent()).hasSize(3);
        assertThat(SmartBIUploadController.filterUploadHistoryByStatus(page(), " ").getContent()).hasSize(3);
    }

    @Test
    void controllerEndpointActuallyAppliesTheFilter() throws Exception {
        String src = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/com/cretas/aims/controller/SmartBIUploadController.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(src).contains("filterUploadHistoryByStatus(\r\n                            pgUploadRepository.findUploadHistoryLightweight(factoryId, pageable), status)"
                .replace("\r\n", src.contains("\r\n") ? "\r\n" : "\n"));
    }
}
