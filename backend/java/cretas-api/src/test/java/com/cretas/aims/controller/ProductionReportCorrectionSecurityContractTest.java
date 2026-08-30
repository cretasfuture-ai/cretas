package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.production.ProductionReportCorrectionRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionReportCorrectionSecurityContractTest {
    @Test
    void submitRequiresProductionManagementPermissionWithoutHardcodedActorRole() throws Exception {
        Method submit = ProductionReportCorrectionController.class.getMethod("submit",
                String.class, Long.class, ProductionReportCorrectionRequest.class, Long.class);
        RequirePermission permission = submit.getAnnotation(RequirePermission.class);

        assertThat(permission).isNotNull();
        assertThat(permission.value()).containsExactly("production:read_write");
        assertThat(submit.getAnnotation(com.cretas.aims.config.RequireRole.class)).isNull();
    }
}
