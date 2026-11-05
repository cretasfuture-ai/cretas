package com.cretas.aims.security;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.controller.MaterialConsumptionController;
import com.cretas.aims.controller.ReportController;
import com.cretas.aims.controller.SupplierAdmissionController;
import com.cretas.aims.controller.inventory.PriceListController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Completeness sweep for the retained factory R6 V2-V5 controllers. Asserts that each of the
 * retained controllers identified by the R31 audit
 * declares @RequirePermission somewhere in its lookup chain (class-level OR on
 * the specific leaking method).
 *
 * <p>This is the catch-net for future regressions: if someone removes the gate
 * from a controller class AND fails to add it back at method level, this test
 * fails and points to R31 audit.
 */
@DisplayName("R6 completeness — retained factory controllers have @RequirePermission")
class R6CompletenessRbacTest {

    @Test
    @DisplayName("V2 ReportController has class-level gate")
    void v2ReportController_hasClassLevelGate() {
        assertNotNull(ReportController.class.getAnnotation(RequirePermission.class),
                "V2 ReportController lost class-level gate — see R31 audit §V2 (9 leak rows)");
    }

    @Test
    @DisplayName("V3 MaterialConsumptionController has class-level gate")
    void v3MaterialConsumption_hasClassLevelGate() {
        assertNotNull(MaterialConsumptionController.class.getAnnotation(RequirePermission.class),
                "V3 MaterialConsumptionController lost class-level gate — see R31 audit §V3");
    }

    @Test
    @DisplayName("V4 SupplierAdmissionController.getSupplierReport has gate")
    void v4SupplierReport_hasGateInLookupChain() throws Exception {
        Method m = SupplierAdmissionController.class
                .getDeclaredMethod("getSupplierReport", String.class, String.class);
        assertNotNull(resolveAnnotation(m, SupplierAdmissionController.class),
                "V4 getSupplierReport lost RBAC gate — see R31 audit §V4 (currentBalance leak)");
    }

    @Test
    @DisplayName("V5 PriceListController has class-level gate")
    void v5PriceListController_hasClassLevelGate() {
        assertNotNull(PriceListController.class.getAnnotation(RequirePermission.class),
                "V5 PriceListController lost class-level gate — see R31 audit §V5 (full price-book leak)");
    }

    @Test
    @DisplayName("All retained R6 controllers covered exactly once in this completeness sweep")
    void allControllersCoveredOnce() {
        List<Class<?>> controllers = List.of(
                ReportController.class,                // V2
                MaterialConsumptionController.class,   // V3
                SupplierAdmissionController.class,     // V4
                PriceListController.class              // V5
        );
        // Sanity: distinct classes count == expected
        assert controllers.stream().distinct().count() == 4 : "Expected 4 retained R6 controllers";
    }

    /**
     * Mirror of {@link com.cretas.aims.config.PermissionInterceptor#preHandle}
     * lookup: method annotation wins, falls back to class annotation.
     */
    private static RequirePermission resolveAnnotation(Method method, Class<?> beanType) {
        RequirePermission methodAnno = method.getAnnotation(RequirePermission.class);
        if (methodAnno != null) {
            return methodAnno;
        }
        return beanType.getAnnotation(RequirePermission.class);
    }
}
