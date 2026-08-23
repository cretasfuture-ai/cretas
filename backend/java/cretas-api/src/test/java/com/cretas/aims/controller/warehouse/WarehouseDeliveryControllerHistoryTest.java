package com.cretas.aims.controller.warehouse;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class WarehouseDeliveryControllerHistoryTest {

    @Test
    void historyIsWarehouseModuleReadOnlyAndTenantScopedByServiceContract() throws Exception {
        Method method = WarehouseDeliveryController.class.getDeclaredMethod(
                "listHistory", String.class, int.class, int.class);

        assertThat(method.getAnnotation(GetMapping.class).value()).containsExactly("/history");
        assertThat(method.getAnnotation(RequireModule.class).value()).isEqualTo("warehouse");
        assertThat(method.getAnnotation(RequirePermission.class).value())
                .containsExactlyInAnyOrder("warehouse:read", "warehouse:read_write");
        assertThat(Arrays.stream(WarehouseDeliveryController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("listHistory")))
                .hasSize(1);
    }
}
