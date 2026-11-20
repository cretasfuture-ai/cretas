package com.cretas.aims.service.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工厂纪律 B9 (Steve 2026-11-10): 报损审批按「报损审批」权限判断, 不再有写死的轨道角色表,
 * 也不再留着「待 Steve 确认」这条悬而未决的注释。
 */
@DisplayName("B9 报损审批: 源码里没有写死的审批角色")
class WastageApprovalPermissionContractTest {

    private static final Path SRC = Path.of(
            "src/main/java/com/cretas/aims/service/inventory/impl/WastageReportServiceImpl.java");

    @Test
    @DisplayName("没有 APPROVER_ROLES 角色表、没有「待 Steve 确认」, 且确实走 WASTAGE_APPROVE_PERMISSION")
    void approvalIsPermissionBased() throws Exception {
        String src = Files.readString(SRC);
        assertThat(src.length()).as("INSTRUMENT DEAD: 读到的源码为空").isGreaterThan(1000);
        assertThat(src).contains("WASTAGE_APPROVE_PERMISSION"); // 阳性对照
        assertThat(src).doesNotContain("待 Steve 确认").doesNotContain("APPROVER_ROLES");
    }
}
