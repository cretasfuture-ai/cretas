package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract for the existing supervisor-only approval authority on report reversal. */
class ReportReversalControllerRoleGateTest {

    private static final Set<String> SUPERVISOR_ROLES = Set.of(
            "factory_super_admin", "production_manager");

    @Test
    void approveAndRejectRequireOnlyExistingSupervisorRoles() throws NoSuchMethodException {
        for (String methodName : Set.of("approveReversal", "rejectReversal")) {
            Method method = Arrays.stream(ReportReversalController.class.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(methodName))
                    .findFirst()
                    .orElseThrow();
            RequireRole roleGate = method.getAnnotation(RequireRole.class);

            assertThat(roleGate).as("%s must have a real role gate", methodName).isNotNull();
            assertThat(new HashSet<>(Arrays.asList(roleGate.value())))
                    .as("%s must reuse the approved supervisor roles", methodName)
                    .isEqualTo(SUPERVISOR_ROLES);
        }
    }
}
