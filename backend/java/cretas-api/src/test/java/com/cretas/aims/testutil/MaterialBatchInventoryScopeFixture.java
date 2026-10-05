package com.cretas.aims.testutil;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.DataScope;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.permission.PlatformRolePermission;
import com.cretas.aims.security.DataScopeContext;
import com.cretas.aims.security.DataScopeResolver;
import com.cretas.aims.service.FuturePlanMatchingService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.service.impl.MaterialBatchServiceImpl;
import com.cretas.aims.utils.ExcelUtil;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/** Shared real-entity rows and repository answers for the inventory scope contracts. */
public final class MaterialBatchInventoryScopeFixture {
    public static final String F006 = "F006";
    public static final String F007 = "F007";
    public static final Long WORKER = 101L;
    public static final Long BOSS = 102L;
    public static final String WAREHOUSE = "WH-RAW";

    private MaterialBatchInventoryScopeFixture() {
    }

    public static MaterialBatchServiceImpl service(MaterialBatchRepository repository,
                                                    DataScopeResolver resolver) {
        MaterialBatchServiceImpl service = new MaterialBatchServiceImpl(
                repository,
                mock(MaterialBatchAdjustmentRepository.class),
                mock(RawMaterialTypeRepository.class),
                new MaterialBatchMapper(),
                mock(MaterialConsumptionRepository.class),
                mock(ProductionPlanBatchUsageRepository.class),
                mock(ExcelUtil.class),
                mock(FuturePlanMatchingService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "dataScopeResolver", resolver);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "bomRecipeItemRepository",
                mock(com.cretas.aims.repository.bom.BomRecipeItemRepository.class));
        User actor = new User();
        actor.setId(WORKER);
        actor.setFactoryId(F006);
        actor.setRoleCode("warehouse_worker");
        actor.setIsActive(true);
        UserRepository userRepository = mock(UserRepository.class);
        org.mockito.Mockito.when(userRepository.findById(WORKER)).thenReturn(Optional.of(actor));
        PlatformRolePermissionRepository platformRepository = mock(PlatformRolePermissionRepository.class);
        com.cretas.aims.repository.config.FactoryModuleConfigRepository factoryRepository =
                mock(com.cretas.aims.repository.config.FactoryModuleConfigRepository.class);
        PermissionRepositoryFixtures.successfulEmpty(platformRepository, factoryRepository);
        org.mockito.Mockito.when(platformRepository.findByRoleCodeAndModuleCodeAndDeletedAtIsNull(
                "warehouse_worker", "inventory"))
                .thenReturn(Optional.of(permission("inventory", "r")));
        org.mockito.Mockito.when(platformRepository.findByRoleCodeAndModuleCodeAndDeletedAtIsNull(
                "warehouse_worker", "production"))
                .thenReturn(Optional.of(permission("production", "r")));
        PermissionServiceImpl permissionService = new PermissionServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(permissionService, "platformRepo", platformRepository);
        org.springframework.test.util.ReflectionTestUtils.setField(permissionService, "factoryConfigRepo", factoryRepository);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "userRepository", userRepository);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "permissionService", permissionService);
        return service;
    }

    private static PlatformRolePermission permission(String module, String level) {
        PlatformRolePermission row = new PlatformRolePermission();
        row.setRoleCode("warehouse_worker");
        row.setModuleCode(module);
        row.setPermissionLevel(level);
        return row;
    }

    public static DataScopeContext scope(DataScope scope, Long userId, String factoryId) {
        return DataScopeContext.builder()
                .userId(userId)
                .factoryId(factoryId)
                .roleCode("warehouse_worker")
                .scope(scope)
                .department("warehouse")
                .column("created_by")
                .build();
    }

    public static MaterialBatch batch(String id, String factoryId, Long creator,
                                      MaterialBatchStatus status, LocalDate expireDate) {
        MaterialBatch batch = new MaterialBatch();
        batch.setId(id);
        batch.setFactoryId(factoryId);
        batch.setBatchNumber("BN-" + id);
        batch.setMaterialTypeId("RM-1");
        batch.setCreatedBy(creator);
        batch.setStatus(status);
        batch.setReceiptDate(LocalDate.now().minusDays(10));
        batch.setExpireDate(expireDate);
        batch.setWarehouseId(WAREHOUSE);
        batch.setReceiptQuantity(new BigDecimal("10"));
        batch.setUsedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setQuantityUnit("kg");
        return batch;
    }

    /**
     * Stubs both the pre-fix and scoped repository methods. Each answer applies the
     * received predicates to the mixed rows; it never returns a preselected fixture row.
     */
    public static void stubQueries(MaterialBatchRepository repository, List<MaterialBatch> rows) {
        doAnswer(invocation -> filtered(rows,
                b -> b.getFactoryId().equals(invocation.getArgument(0))
                        && b.getStatus() == invocation.getArgument(1)))
                .when(repository).findByFactoryIdAndStatus(anyString(), any(MaterialBatchStatus.class));
        doAnswer(invocation -> filtered(rows,
                b -> b.getFactoryId().equals(invocation.getArgument(0))
                        && b.getStatus() == invocation.getArgument(1)
                        && ((Collection<Long>) invocation.getArgument(2)).contains(b.getCreatedBy())))
                .when(repository).findByFactoryIdAndStatusAndCreatedByIn(anyString(), any(MaterialBatchStatus.class), anyCollection());
        doAnswer(invocation -> filtered(rows,
                b -> b.getFactoryId().equals(invocation.getArgument(0))
                        && b.getStatus() == invocation.getArgument(1)
                        && invocation.getArgument(2).equals(b.getWarehouseId())))
                .when(repository).findByFactoryIdAndStatusAndWarehouseId(anyString(), any(MaterialBatchStatus.class), anyString());
        doAnswer(invocation -> filtered(rows,
                b -> b.getFactoryId().equals(invocation.getArgument(0))
                        && b.getStatus() == invocation.getArgument(1)
                        && ((Collection<Long>) invocation.getArgument(2)).contains(b.getCreatedBy())
                        && invocation.getArgument(3).equals(b.getWarehouseId())))
                .when(repository).findByFactoryIdAndStatusAndCreatedByInAndWarehouseId(
                        anyString(), any(MaterialBatchStatus.class), anyCollection(), anyString());
        doAnswer(invocation -> filtered(rows,
                b -> b.getFactoryId().equals(invocation.getArgument(0))
                        && b.getStatus() == invocation.getArgument(1)
                        && ((Collection<String>) invocation.getArgument(2)).contains(b.getMaterialTypeId())))
                .when(repository).findByFactoryIdAndStatusAndMaterialTypeIdIn(
                        anyString(), any(MaterialBatchStatus.class), anyCollection());
        doAnswer(invocation -> filtered(rows,
                b -> b.getFactoryId().equals(invocation.getArgument(0))
                        && b.getStatus() == invocation.getArgument(1)
                        && ((Collection<String>) invocation.getArgument(2)).contains(b.getMaterialTypeId())
                        && invocation.getArgument(3).equals(b.getWarehouseId())))
                .when(repository).findByFactoryIdAndStatusAndMaterialTypeIdInAndWarehouseId(
                        anyString(), any(MaterialBatchStatus.class), anyCollection(), anyString());

        doAnswer(invocation -> filtered(rows,
                b -> b.getFactoryId().equals(invocation.getArgument(0))
                        && b.getExpireDate() != null
                        && !b.getExpireDate().isBefore(LocalDate.now())
                        && !b.getExpireDate().isAfter(invocation.getArgument(1))))
                .when(repository).findExpiringBatches(anyString(), any(LocalDate.class));
        doAnswer(invocation -> filtered(rows,
                b -> b.getFactoryId().equals(invocation.getArgument(0))
                        && b.getExpireDate() != null
                        && !b.getExpireDate().isBefore(LocalDate.now())
                        && !b.getExpireDate().isAfter(invocation.getArgument(1))
                        && ((Collection<Long>) invocation.getArgument(2)).contains(b.getCreatedBy())))
                .when(repository).findExpiringBatchesByCreatedByIn(anyString(), any(LocalDate.class), anyCollection());
        doAnswer(invocation -> filtered(rows,
                b -> b.getFactoryId().equals(invocation.getArgument(0))
                        && b.getStatus() != MaterialBatchStatus.EXPIRED
                        && b.getExpireDate() != null
                        && b.getExpireDate().isBefore(invocation.getArgument(1))))
                .when(repository).findExpiredBatches(anyString());
        doAnswer(invocation -> filtered(rows,
                b -> b.getFactoryId().equals(invocation.getArgument(0))
                        && b.getStatus() != MaterialBatchStatus.EXPIRED
                        && b.getExpireDate() != null
                        && b.getExpireDate().isBefore(invocation.getArgument(1))
                        && ((Collection<Long>) invocation.getArgument(2)).contains(b.getCreatedBy())))
                .when(repository).findExpiredBatchesByCreatedByIn(anyString(), any(LocalDate.class), anyCollection());
    }

    private static List<MaterialBatch> filtered(List<MaterialBatch> rows, Predicate<MaterialBatch> predicate) {
        return rows.stream().filter(predicate).toList();
    }
}
