package com.cretas.aims.controller.inventory;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.config.RequireRole;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PathVariable;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The HTTP boundary must keep both permission and role gates before service locks. */
class PurchaseInvoiceControllerTenantGateTest {

    @Test
    void mutationEndpointsRequireTenantPathAndBothBackendGates() throws Exception {
        assertThat(PurchaseInvoiceController.class.getAnnotation(RequirePermission.class).value())
                .contains("finance:read_write", "procurement:read_write");

        Method upload = PurchaseInvoiceController.class.getMethod(
                "upload", String.class, String.class, Map.class);
        Method reconcile = PurchaseInvoiceController.class.getMethod(
                "reconcile", String.class, String.class, String.class, Map.class);

        for (Method method : new Method[] {upload, reconcile}) {
            assertThat(method.getAnnotation(RequireRole.class)).isNotNull();
            Parameter factoryId = method.getParameters()[0];
            assertThat(factoryId.getAnnotation(PathVariable.class)).isNotNull();
            assertThat(factoryId.getAnnotation(PathVariable.class).value()).isEqualTo("factoryId");
        }
    }
}
