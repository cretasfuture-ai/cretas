package com.cretas.aims.controller;

import com.cretas.aims.config.ModuleEnabledInterceptor;
import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.entity.DisposalRecord;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.UserModuleAccess;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.DisposalRecordRepository;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.UserModuleAccessRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsAdjustmentLogRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.DisposalRecordService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.config.FactoryConfigService;
import com.cretas.aims.service.finance.DisposalCostAccountingService;
import com.cretas.aims.service.impl.UserModuleAccessServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D-S302-03 (Owner 2026-11-04): 报废审批要财务经理或工厂管理员, 财务经理却被质检模块闸拦成 403。
 * 放开财务经理走完审批这一条路径, 不给财务质检模块的写权限, 质检员自批 / 冒名的拦截不松。
 *
 * <p>入口是真的: MockMvc + 真 ModuleEnabledInterceptor + 真 PermissionInterceptor + 真 DisposalController
 * + 真 DisposalRecordService (角色闸在 service 里, 用桩就量不到它)。只有仓储和权限矩阵是桩。
 */
class DisposalFinanceApprovalAccessContractTest {

    private static final String BASE = "/api/mobile/F006/disposal-records";

    /** L1 权威矩阵里这几个角色的相关行 (V20261030_14 之后财务只剩 finance)。 */
    private static final Map<String, Set<String>> ROLE_PERMISSIONS = Map.of(
            "finance_manager", Set.of("finance:read", "finance:read_write"),
            "cashier", Set.of("finance:read", "finance:read_write"),
            "quality_manager", Set.of("quality:read", "quality:read_write"),
            "quality_inspector", Set.of("quality:read", "quality:read_write"),
            "warehouse_manager", Set.of("warehouse:read", "warehouse:read_write"));

    private final Map<Long, User> users = Map.of(
            1L, user(1L, "finance_manager"),
            2L, user(2L, "cashier"),
            3L, user(3L, "quality_manager"),
            4L, user(4L, "quality_inspector"));

    private FactoryConfigService factoryConfig;
    private UserModuleAccessRepository moduleOverrides;
    private DisposalRecordRepository records;
    private DisposalRecord pending;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        factoryConfig = mock(FactoryConfigService.class);
        when(factoryConfig.isModuleEnabled(eq("F006"), any())).thenReturn(true);

        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findById(any())).thenAnswer(call -> Optional.ofNullable(users.get(call.getArgument(0))));

        PermissionService permissions = mock(PermissionService.class, call -> {
            if (!"hasAnyPermission".equals(call.getMethod().getName())) {
                return Mockito.RETURNS_DEFAULTS.answer(call);
            }
            // varargs: getArguments() 会把数组摊开, 按 raw 取才是调用方传的那一组码
            User actor = (User) call.getRawArguments()[0];
            Set<String> held = ROLE_PERMISSIONS.getOrDefault(actor.getRoleCode(), Set.of());
            for (String code : (String[]) call.getRawArguments()[1]) {
                if (held.contains(code)) return true;
            }
            return false;
        });

        moduleOverrides = mock(UserModuleAccessRepository.class);
        when(moduleOverrides.findActive(any(), any(), any())).thenReturn(Optional.empty());
        UserModuleAccessServiceImpl moduleAccess = new UserModuleAccessServiceImpl(moduleOverrides, permissions);

        records = mock(DisposalRecordRepository.class);
        pending = pendingRecord();
        when(records.findByIdAndFactoryIdForUpdate(7L, "F006")).thenReturn(Optional.of(pending));
        when(records.findById(7L)).thenReturn(Optional.of(pending));
        when(records.save(any())).thenAnswer(call -> call.getArgument(0));
        when(records.findPendingApprovals("F006")).thenReturn(List.of(pending));
        when(records.findAll(Mockito.<org.springframework.data.jpa.domain.Specification<DisposalRecord>>any(),
                any(org.springframework.data.domain.Pageable.class))).thenReturn(new PageImpl<>(List.of(pending)));

        DisposalRecordService service = new DisposalRecordService(records, mock(MaterialBatchRepository.class),
                mock(MaterialBatchAdjustmentRepository.class), mock(ProductionBatchRepository.class),
                mock(FinishedGoodsBatchRepository.class), mock(FinishedGoodsAdjustmentLogRepository.class));
        ReflectionTestUtils.setField(service, "disposalCostAccountingService", mock(DisposalCostAccountingService.class));

        mvc = MockMvcBuilders.standaloneSetup(new DisposalController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(
                        new ModuleEnabledInterceptor(factoryConfig, moduleAccess, userRepository, new ObjectMapper()),
                        new PermissionInterceptor(permissions, userRepository, new ObjectMapper()))
                .build();
    }

    @Test
    @DisplayName("阳性: 财务经理走完审批 —— 看待审批 / 看详情 / 批准, 批准人取自登录态")
    void financeManagerWalksTheApprovalPath() throws Exception {
        as(1L, get(BASE + "/pending")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(7));
        as(1L, get(BASE)).andExpect(status().isOk());
        as(1L, get(BASE + "/7")).andExpect(status().isOk());

        // 请求体里塞别人的 approverId: 不认, 审批人只取登录态
        as(1L, put(BASE + "/7/approve").contentType(MediaType.APPLICATION_JSON).content("{\"approverId\":999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(pending.getStatus()).isEqualTo("APPROVED");
        assertThat(pending.getApprovedBy()).isEqualTo(1);
    }

    @Test
    @DisplayName("阳性: 财务经理也能驳回 (同一道角色闸, 只放批准会卡在另一半)")
    void financeManagerCanReject() throws Exception {
        as(1L, put(BASE + "/7/reject").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"证据不足\"}"))
                .andExpect(status().isOk());
        assertThat(pending.getStatus()).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("阴性: 只放这一条路径 —— 财务经理建 / 改 / 删 / 统计仍被模块闸拦住")
    void financeManagerGetsNoOtherQualityWrite() throws Exception {
        as(1L, post(BASE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("quality_inspection")));
        as(1L, put(BASE + "/7").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        as(1L, delete(BASE + "/7")).andExpect(status().isForbidden());
        as(1L, get(BASE + "/stats?startDate=2026-01-01T00:00:00&endDate=2026-02-01T00:00:00"))
                .andExpect(status().isForbidden());
        verify(records, never()).delete(any(DisposalRecord.class));
    }

    @Test
    @DisplayName("阴性: 同持 finance 的出纳不在放行名单里, 被模块闸拦住")
    void cashierStaysBlocked() throws Exception {
        as(2L, put(BASE + "/7/approve").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("quality_inspection")));
        assertThat(pending.getStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("阴性: 质检员 / 质量经理有模块但角色不符, 仍被 service 的角色闸拦住 (自批不松)")
    void qualityRolesStillCannotApprove() throws Exception {
        for (Long userId : List.of(3L, 4L)) {
            as(userId, put(BASE + "/7/approve").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("财务经理或工厂管理员")));
            as(userId, put(BASE + "/7/reject").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("财务经理或工厂管理员")));
        }
        assertThat(pending.getStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("阴性: 账号上显式 DENY 质检模块的财务经理不放行 (放行名单不盖过管理员的决定)")
    void explicitDenyWins() throws Exception {
        UserModuleAccess deny = new UserModuleAccess();
        deny.setAccessType(UserModuleAccess.AccessType.DENY);
        when(moduleOverrides.findActive("F006", "1", "quality_inspection")).thenReturn(Optional.of(deny));

        as(1L, put(BASE + "/7/approve").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        assertThat(pending.getStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("阴性: 工厂关了质检模块, 财务经理同样走不了 (工厂级开关不被绕过)")
    void factorySwitchStillApplies() throws Exception {
        when(factoryConfig.isModuleEnabled("F006", "quality_inspection")).thenReturn(false);
        as(1L, put(BASE + "/7/approve").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        assertThat(pending.getStatus()).isEqualTo("PENDING");
    }

    private ResultActions as(Long userId, MockHttpServletRequestBuilder request) throws Exception {
        User user = users.get(userId);
        return mvc.perform(request
                .requestAttr("userId", userId)
                .requestAttr("username", user.getUsername())
                .requestAttr("role", user.getRoleCode()));
    }

    private static User user(Long id, String roleCode) {
        User user = new User();
        user.setId(id);
        user.setFactoryId("F006");
        user.setUsername("u-" + roleCode);
        user.setRoleCode(roleCode);
        return user;
    }

    private static DisposalRecord pendingRecord() {
        DisposalRecord record = new DisposalRecord();
        record.setId(7L);
        record.setFactoryId("F006");
        record.setDisposalType("SCRAP");
        record.setDisposalQuantity(new BigDecimal("1"));
        record.setEstimatedLoss(BigDecimal.ZERO);
        record.setApprovalStatus("PENDING");
        return record;
    }
}
