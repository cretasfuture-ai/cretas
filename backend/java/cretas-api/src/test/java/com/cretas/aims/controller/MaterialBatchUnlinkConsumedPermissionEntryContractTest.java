package com.cretas.aims.controller;

import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.datacenter.OperationLog;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.datacenter.OperationLogRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.security.PriceMaskResolver;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.canvas.ThresholdResolverService;
import com.cretas.aims.service.impl.MaterialBatchServiceImpl;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.service.inventory.OpeningInventoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #1968 M-3: 「解除已消耗批次挂载」权限点的默认授权必须在<b>真实入口</b>上起作用。
 *
 * <p>上一版默认授给质量经理, 只在 service 单测里传角色串证明「能解除」; 真实请求先过端点上的
 * {@code @RequirePermission(warehouse/inventory 写)}, 质量经理在拦截器就被 403, 到不了 service (形态 B)。
 * 这里串起: MockMvc → 真 PermissionInterceptor → 真 MaterialBatchController → 真 MaterialBatchServiceImpl
 * → 真 PermissionServiceImpl (L1/L2 空 ⇒ 硬编码矩阵; 可配置名单未配 ⇒ 代码默认), 仓储全部 mock。
 */
class MaterialBatchUnlinkConsumedPermissionEntryContractTest {

    private static final String F = "F-UNLINK-ENTRY";
    private static final String URL = "/api/mobile/" + F + "/material-batches/B-1/source-individual/unlink";

    private final Map<Long, User> users = new HashMap<>();
    private long nextId = 7000;
    private MaterialBatchRepository batchRepo;
    private OperationLogRepository logRepo;
    private MaterialBatch batch;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        PlatformRolePermissionRepository platformRepo = mock(PlatformRolePermissionRepository.class);
        when(platformRepo.findByRoleCodeAndModuleCodeAndDeletedAtIsNull(anyString(), anyString())).thenReturn(Optional.empty());
        when(platformRepo.findByRoleCodeAndDeletedAtIsNull(anyString())).thenReturn(new ArrayList<>());
        FactoryModuleConfigRepository factoryRepo = mock(FactoryModuleConfigRepository.class);
        when(factoryRepo.findByFactoryIdAndConfigVersion(anyString(), eq(1))).thenReturn(List.of());
        ThresholdResolverService thresholds = mock(ThresholdResolverService.class); // 未配 ⇒ 代码默认名单
        PermissionServiceImpl permissions = new PermissionServiceImpl();
        ReflectionTestUtils.setField(permissions, "platformRepo", platformRepo);
        ReflectionTestUtils.setField(permissions, "factoryConfigRepo", factoryRepo);
        ReflectionTestUtils.setField(permissions, "thresholdResolver", thresholds);

        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(users.get(inv.<Long>getArgument(0))));

        batchRepo = mock(MaterialBatchRepository.class);
        logRepo = mock(OperationLogRepository.class);
        MaterialBatchMapper mapper = mock(MaterialBatchMapper.class);
        when(batchRepo.save(any(MaterialBatch.class))).thenAnswer(i -> i.getArgument(0));
        when(mapper.toDTO(any(MaterialBatch.class))).thenAnswer(i -> {
            MaterialBatch b = i.getArgument(0);
            return MaterialBatchDTO.builder().id(b.getId()).sourceIndividualId(b.getSourceIndividualId()).build();
        });
        MaterialConsumptionRepository consumptionRepo = mock(MaterialConsumptionRepository.class);
        when(consumptionRepo.findByFactoryIdAndBatchIdPaged(anyString(), anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<MaterialConsumption>(List.of()));
        MaterialBatchServiceImpl service = new MaterialBatchServiceImpl(
                batchRepo, null, null, mapper, consumptionRepo, null, null, null);
        ReflectionTestUtils.setField(service, "operationLogRepository", logRepo);
        ReflectionTestUtils.setField(service, "permissionService", permissions);

        batch = new MaterialBatch();
        batch.setId("B-1");
        batch.setFactoryId(F);
        batch.setBatchNumber("MB-ENTRY-1");
        batch.setCreatedBy(1L);
        batch.setQuantityUnit("kg");
        batch.setUsedQuantity(new BigDecimal("3")); // 已领用 ⇒ 走「解除已消耗批次挂载」那道门
        batch.setSourceIndividualId("SI-OLD");
        when(batchRepo.findById("B-1")).thenReturn(Optional.of(batch));

        MobileService mobileService = mock(MobileService.class);
        when(mobileService.getUserFromToken(anyString())).thenReturn(UserDTO.builder().id(42L).build());
        MaterialBatchController controller = new MaterialBatchController(
                service, mobileService, mock(PriceMaskResolver.class), mock(OpeningInventoryService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new PermissionInterceptor(permissions, userRepository, new ObjectMapper()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private long login(FactoryUserRole role) {
        User u = new User();
        u.setId(nextId++);
        u.setRoleCode(role.name());
        u.setFactoryId(F);
        u.setIsActive(true);
        users.put(u.getId(), u);
        return u.getId();
    }

    private ResultActions unlinkAs(FactoryUserRole role) throws Exception {
        return mvc.perform(post(URL).header("Authorization", "Bearer t")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"挂错了个体\"}")
                .requestAttr("userId", login(role))
                .requestAttr("role", role.name()));
    }

    @Test
    @DisplayName("默认授权: 仓储主管经真实入口解除已领用批次 → 200, 挂载清空, 写了操作日志")
    void warehouseManagerUnlinksConsumedBatchByDefault() throws Exception {
        unlinkAs(FactoryUserRole.warehouse_manager)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(batch.getSourceIndividualId()).isNull();
        verify(logRepo).save(any(OperationLog.class));
    }

    @Test
    @DisplayName("有仓储写权限但不在默认名单的仓库员 → 过了拦截器, 被权限点拦成 409 SOURCE_INDIVIDUAL_LINK_CONSUMED, 不改不写")
    void warehouseWorkerWithoutThePermissionGets409() throws Exception {
        unlinkAs(FactoryUserRole.warehouse_worker)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));

        assertThat(batch.getSourceIndividualId()).isEqualTo("SI-OLD");
        verify(batchRepo, never()).save(any());
        verify(logRepo, never()).save(any());
    }
}
