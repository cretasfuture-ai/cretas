package com.cretas.aims.testutil;

import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/** Shared repository wiring for permission tests: an empty result is a successful read. */
public final class PermissionRepositoryFixtures {
    private PermissionRepositoryFixtures() {}

    public static void successfulEmpty(
            PlatformRolePermissionRepository platformRepo,
            FactoryModuleConfigRepository factoryRepo) {
        lenient().when(platformRepo.findByRoleCodeAndDeletedAtIsNull(anyString())).thenReturn(java.util.List.of());
        lenient().when(platformRepo.findByRoleCodeAndModuleCodeAndDeletedAtIsNull(anyString(), anyString()))
                .thenReturn(java.util.Optional.empty());
        lenient().when(factoryRepo.findByFactoryIdAndConfigVersion(anyString(), anyInt())).thenReturn(java.util.List.of());
    }

    public static void installSuccessfulEmpty(PermissionServiceImpl service) {
        PlatformRolePermissionRepository platformRepo = mock(PlatformRolePermissionRepository.class);
        FactoryModuleConfigRepository factoryRepo = mock(FactoryModuleConfigRepository.class);
        successfulEmpty(platformRepo, factoryRepo);
        ReflectionTestUtils.setField(service, "platformRepo", platformRepo);
        ReflectionTestUtils.setField(service, "factoryConfigRepo", factoryRepo);
    }
}
