package com.cretas.aims.controller.sales;

import com.cretas.aims.config.ModuleEnabledInterceptor;
import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.UserModuleAccess;
import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import com.cretas.aims.entity.factory.WarehouseCodes;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.sales.SalesDeliveryItemBatchAllocation;
import com.cretas.aims.entity.sales.SalesPackagingConversionConfirmation;
import com.cretas.aims.repository.sales.SalesPackagingConversionConfirmationRepository;
import com.cretas.aims.dto.sales.BatchAllocationDTO;
import com.cretas.aims.service.sales.AutoAllocationOutcome;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.UserModuleAccessRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryItemRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.sales.SalesDeliveryItemBatchAllocationRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.config.FactoryConfigService;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.impl.UserModuleAccessServiceImpl;
import com.cretas.aims.service.inventory.FgReservationLedgerService;
import com.cretas.aims.service.sales.impl.SalesDeliveryBatchAllocationServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D-S309-01 + O-309-1 (Owner 2026-11-04)。
 *
 * <p>D-S309-01: 批次分配放宽给仓管, 但 sales_order 模块闸把仓管拦成 403。放开仓管, 其他角色范围不变,
 * 隔离批的 409 不受影响。O-309-1: 成品批规格和发货行不一致时要拦住并要求换算确认, 缺规格不猜。
 *
 * <p>入口是真的: MockMvc + 真 ModuleEnabledInterceptor + 真 PermissionInterceptor + 真控制器 + 真
 * SalesDeliveryBatchAllocationServiceImpl。夹具取自真实形状: 行 1箱=8盒, 批 1托盘=96盒, 批单位 盒。
 */
class BatchAllocationRulesContractTest {

    private static final String FACTORY = "F006";
    private static final String BASE = "/api/mobile/F006/sales-deliveries/items/271/batch-allocations";
    private static final String PRODUCT = "SKU-BEEF";
    private static final long SALES = 1L;
    private static final long WAREHOUSE_MANAGER = 2L;
    private static final long WAREHOUSE_WORKER = 3L;
    private static final long FINANCE = 4L;

    /** L1 权威矩阵: 仓管的 sales 已被 V20261030_13 收成 '-', 所以这里没有 sales 的任何码。 */
    private static final Map<String, Set<String>> ROLE_PERMISSIONS = Map.of(
            "sales_manager", Set.of("sales:read", "sales:read_write"),
            "warehouse_manager", Set.of("warehouse:read", "warehouse:read_write"),
            "warehouse_worker", Set.of("warehouse:read", "warehouse:read_write"),
            "finance_manager", Set.of("finance:read", "finance:read_write"));

    private final Map<Long, User> users = Map.of(
            SALES, user(SALES, "sales_manager"),
            WAREHOUSE_MANAGER, user(WAREHOUSE_MANAGER, "warehouse_manager"),
            WAREHOUSE_WORKER, user(WAREHOUSE_WORKER, "warehouse_worker"),
            FINANCE, user(FINANCE, "finance_manager"));

    private final List<FinishedGoodsBatch> batches = new ArrayList<>();
    private SalesDeliveryItem line;
    private SalesDeliveryItemBatchAllocationRepository allocations;
    private UserModuleAccessRepository moduleOverrides;
    private SalesDeliveryBatchAllocationServiceImpl service;
    private SalesPackagingConversionConfirmationRepository confirmations;
    private final List<SalesPackagingConversionConfirmation> confirmationRows = new ArrayList<>();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        FactoryConfigService factoryConfig = mock(FactoryConfigService.class);
        when(factoryConfig.isModuleEnabled(eq(FACTORY), any())).thenReturn(true);

        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findById(any())).thenAnswer(call -> Optional.ofNullable(users.get(call.getArgument(0))));

        PermissionService permissions = mock(PermissionService.class, call -> {
            if (!"hasAnyPermission".equals(call.getMethod().getName())) {
                return Mockito.RETURNS_DEFAULTS.answer(call);
            }
            User actor = (User) call.getRawArguments()[0];
            Set<String> held = ROLE_PERMISSIONS.getOrDefault(actor.getRoleCode(), Set.of());
            for (String code : (String[]) call.getRawArguments()[1]) {
                if (held.contains(code)) return true;
            }
            return false;
        });
        moduleOverrides = mock(UserModuleAccessRepository.class);
        when(moduleOverrides.findActive(any(), any(), any())).thenReturn(Optional.empty());

        line = line("箱", "箱", "盒", new BigDecimal("8"));
        SalesDeliveryItemRepository items = mock(SalesDeliveryItemRepository.class);
        when(items.findByIdForUpdate(anyLong())).thenAnswer(call -> Optional.of(line));
        when(items.findById(anyLong())).thenAnswer(call -> Optional.of(line));

        FinishedGoodsBatchRepository goods = mock(FinishedGoodsBatchRepository.class);
        when(goods.findByIdAndFactoryIdForUpdate(any(), eq(FACTORY))).thenAnswer(call -> batches.stream()
                .filter(b -> b.getId().equals(call.getArgument(0))).findFirst());
        when(goods.findAvailableBatchesFefoAllWarehousesExcluding(FACTORY, PRODUCT, WarehouseCodes.WH_RD))
                .thenAnswer(call -> List.copyOf(batches));

        allocations = mock(SalesDeliveryItemBatchAllocationRepository.class);
        when(allocations.findByFactoryIdAndDeliveryItemId(FACTORY, "271")).thenReturn(List.of());
        when(allocations.findByFactoryIdAndFinishedGoodsBatchId(eq(FACTORY), any())).thenReturn(List.of());

        ProductTypeRepository productTypes = mock(ProductTypeRepository.class);
        when(productTypes.findById(any())).thenReturn(Optional.empty());

        service = new SalesDeliveryBatchAllocationServiceImpl(allocations, items, goods, mock(WarehouseResolver.class),
                productTypes, mock(SalesOrderRepository.class), mock(FgReservationLedgerService.class),
                mock(SalesDeliveryRecordRepository.class));
        confirmations = mock(SalesPackagingConversionConfirmationRepository.class);
        when(confirmations.save(any())).thenAnswer(call -> {
            confirmationRows.add(call.getArgument(0));
            return call.getArgument(0);
        });
        org.springframework.test.util.ReflectionTestUtils.setField(service, "conversionConfirmationRepository", confirmations);

        mvc = MockMvcBuilders.standaloneSetup(new SalesDeliveryBatchAllocationController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(
                        new ModuleEnabledInterceptor(factoryConfig, new UserModuleAccessServiceImpl(moduleOverrides, permissions),
                                userRepository, new ObjectMapper()),
                        new PermissionInterceptor(permissions, userRepository, new ObjectMapper()))
                .build();
    }

    // ==================== D-S309-01 ====================

    @Test
    @DisplayName("复现 D-S309-01: 仓管分配和清空都放行 (仓管重新分配要先清空, 只放一处会卡第二步)")
    void warehouseManagerCanAllocateAndClear() throws Exception {
        batch("FG-SAME", "盒", "箱", "盒", "8", FinishedGoodsBatch.Status.AVAILABLE);

        as(WAREHOUSE_MANAGER, allocate("FG-SAME", "1", null)).andExpect(status().isOk());
        verify(allocations, times(1)).saveAll(anyList());

        as(WAREHOUSE_MANAGER, delete(BASE)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("阳性对照: 销售范围不变")
    void salesStillAllocates() throws Exception {
        batch("FG-SAME", "盒", "箱", "盒", "8", FinishedGoodsBatch.Status.AVAILABLE);
        as(SALES, allocate("FG-SAME", "1", null)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("阴性: 同持 warehouse:read_write 的仓库员和别的角色范围不变, 被模块闸拦住, 什么都没写")
    void otherRolesStayBlocked() throws Exception {
        batch("FG-SAME", "盒", "箱", "盒", "8", FinishedGoodsBatch.Status.AVAILABLE);
        for (long userId : new long[]{WAREHOUSE_WORKER, FINANCE}) {
            as(userId, allocate("FG-SAME", "1", null))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(Matchers.containsString("sales_order")));
            as(userId, delete(BASE)).andExpect(status().isForbidden());
        }
        verify(allocations, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("阴性: 账号上显式 DENY 销售模块的仓管不放行")
    void explicitDenyWins() throws Exception {
        UserModuleAccess deny = new UserModuleAccess();
        deny.setAccessType(UserModuleAccess.AccessType.DENY);
        when(moduleOverrides.findActive(FACTORY, String.valueOf(WAREHOUSE_MANAGER), "sales_order"))
                .thenReturn(Optional.of(deny));
        batch("FG-SAME", "盒", "箱", "盒", "8", FinishedGoodsBatch.Status.AVAILABLE);

        as(WAREHOUSE_MANAGER, allocate("FG-SAME", "1", null)).andExpect(status().isForbidden());
        verify(allocations, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("隔离批的 409 不受影响: 仓管分配 DEFECTIVE, 确认换算也不行, 且先报不可售")
    void quarantinedBatchStaysNotSaleable() throws Exception {
        batch("FG-QUARANTINE", "盒", "托盘", "盒", "96", FinishedGoodsBatch.Status.DEFECTIVE);

        for (Boolean confirmed : new Boolean[]{null, true}) {
            as(WAREHOUSE_MANAGER, allocate("FG-QUARANTINE", "1", confirmed))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("FINISHED_GOODS_BATCH_NOT_SALEABLE"));
        }
        verify(allocations, never()).saveAll(anyList());
    }

    // ==================== O-309-1 ====================

    @Test
    @DisplayName("复现 O-309-1: 行 1箱=8盒, 批 1托盘=96盒, 没确认 → 409 + 错误码 + 中文说清两边规格, 不落库")
    void mismatchedSpecIsBlocked() throws Exception {
        batch("FG02", "盒", "托盘", "盒", "96", FinishedGoodsBatch.Status.AVAILABLE);

        as(WAREHOUSE_MANAGER, allocate("FG02", "1", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("BATCH_PACKAGING_SPEC_MISMATCH"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(Matchers.allOf(
                        Matchers.containsString("1托盘=96盒"), Matchers.containsString("1箱=8盒"),
                        Matchers.containsString("确认换算"))));
        as(WAREHOUSE_MANAGER, allocate("FG02", "1", false))
                .andExpect(jsonPath("$.errorCode").value("BATCH_PACKAGING_SPEC_MISMATCH"));
        verify(allocations, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("存量包装英文码经同一展示映射变成中文，错误仍阻断分配")
    void historicalCodesAreTranslatedInMismatchMessage() throws Exception {
        line = line("case", "case", "box", new BigDecimal("8"));
        batch("FG-CODES", "box", "case", "box", "96", FinishedGoodsBatch.Status.AVAILABLE);

        as(WAREHOUSE_MANAGER, allocate("FG-CODES", "1", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("BATCH_PACKAGING_SPEC_MISMATCH"))
                .andExpect(jsonPath("$.message").value(Matchers.allOf(
                        Matchers.containsString("1箱=96盒"), Matchers.containsString("1箱=8盒"),
                        Matchers.not(Matchers.containsString("case")), Matchers.not(Matchers.containsString("box")))));
        verify(allocations, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("确认换算后放行, 分配量仍按发货行单位记")
    @SuppressWarnings("unchecked")
    void confirmedConversionGoesThrough() throws Exception {
        batch("FG02", "盒", "托盘", "盒", "96", FinishedGoodsBatch.Status.AVAILABLE);

        as(WAREHOUSE_MANAGER, allocate("FG02", "1", true)).andExpect(status().isOk());

        ArgumentCaptor<List<SalesDeliveryItemBatchAllocation>> saved = ArgumentCaptor.forClass(List.class);
        verify(allocations).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(1);
        assertThat(saved.getValue().get(0).getAllocatedQty()).isEqualByComparingTo("1");
        assertThat(saved.getValue().get(0).getUnit()).isEqualTo("箱");
    }

    @Test
    @DisplayName("确认只跳过规格闸: 确认了也不能超过换算后的可用量 (96 盒 = 12 箱)")
    void confirmationDoesNotBypassAvailability() throws Exception {
        batch("FG02", "盒", "托盘", "盒", "96", FinishedGoodsBatch.Status.AVAILABLE);
        line.setDeliveredQuantity(new BigDecimal("20"));

        as(WAREHOUSE_MANAGER, allocate("FG02", "13", true))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("可用库存不足")));
    }

    @Test
    @DisplayName("规格一致照常: 系数写法不同 (8 对 8.00000000) 也算一致, 不用确认")
    void sameSpecNeedsNoConfirmation() throws Exception {
        batch("FG-SAME", "盒", "箱", "盒", "8.00000000", FinishedGoodsBatch.Status.AVAILABLE);
        as(SALES, allocate("FG-SAME", "1", null)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("同样是箱, 一箱的量不同也算不一致 (比的是规格, 不是单位名)")
    void sameUnitDifferentFactorIsMismatch() throws Exception {
        batch("FG-12", "盒", "箱", "盒", "12", FinishedGoodsBatch.Status.AVAILABLE);
        as(SALES, allocate("FG-12", "1", null))
                .andExpect(jsonPath("$.errorCode").value("BATCH_PACKAGING_SPEC_MISMATCH"));
    }

    @Test
    @DisplayName("批没有规格: 不判一致也不判不一致, 按发货行自己的规格换算 (生产产出的成品都是这样)")
    void batchWithoutSpecUsesTheLinesOwnSpec() throws Exception {
        FinishedGoodsBatch noSpec = batch("FG-PLAIN", "盒", null, null, null, FinishedGoodsBatch.Status.AVAILABLE);
        noSpec.setProducedQuantity(new BigDecimal("8"));

        as(SALES, allocate("FG-PLAIN", "1", null)).andExpect(status().isOk());
        as(SALES, allocate("FG-PLAIN", "2", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("可用库存不足")));
    }

    @Test
    @DisplayName("行没有规格、单位又不同: 不猜换算, 如实 409 BATCH_UNIT_CONVERSION_UNRESOLVED")
    void missingSpecIsNotGuessed() throws Exception {
        line = line("箱", null, null, null);
        batch("FG02", "盒", "托盘", "盒", "96", FinishedGoodsBatch.Status.AVAILABLE);

        for (Boolean confirmed : new Boolean[]{null, true}) {
            as(SALES, allocate("FG02", "1", confirmed))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("BATCH_UNIT_CONVERSION_UNRESOLVED"));
        }
        verify(allocations, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("规格只填了一半算缺规格: 不比较, 不拦")
    void partialSpecCountsAsMissing() throws Exception {
        line = line("盒", "箱", null, new BigDecimal("8"));
        batch("FG02", "盒", "托盘", "盒", "96", FinishedGoodsBatch.Status.AVAILABLE);
        as(SALES, allocate("FG02", "1", null)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("推荐接口标出规格不一致的批次和两边规格; skipPackagingMismatch 时不列它")
    void recommendFlagsMismatch() throws Exception {
        batch("FG02", "盒", "托盘", "盒", "96", FinishedGoodsBatch.Status.AVAILABLE);
        batch("FG-SAME", "盒", "箱", "盒", "8", FinishedGoodsBatch.Status.AVAILABLE);
        String query = "/recommend-fifo?productTypeId=" + PRODUCT + "&requiredQty=1&unit=箱";

        as(SALES, get(BASE + query))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].batchId").value("FG02"))
                .andExpect(jsonPath("$.data[0].packagingMismatch").value(true))
                .andExpect(jsonPath("$.data[0].batchPackagingLabel").value("1托盘=96盒"))
                .andExpect(jsonPath("$.data[0].linePackagingLabel").value("1箱=8盒"));

        as(SALES, get(BASE + query + "&skipPackagingMismatch=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].batchId").value("FG-SAME"))
                .andExpect(jsonPath("$.data[0].packagingMismatch").value(false));
    }

    @Test
    @DisplayName("自动分配 (子发运预填 / 确认发货兜底) 没有人确认, 绕开规格不一致的批次, 挑规格一致的")
    @SuppressWarnings("unchecked")
    void autoAllocationSkipsMismatchedBatch() {
        batch("FG02", "盒", "托盘", "盒", "96", FinishedGoodsBatch.Status.AVAILABLE);
        batch("FG-SAME", "盒", "箱", "盒", "8", FinishedGoodsBatch.Status.AVAILABLE);

        assertThat(service.autoAllocateFifo(FACTORY, "271", PRODUCT, new BigDecimal("1"), "箱", null)).isTrue();

        ArgumentCaptor<List<SalesDeliveryItemBatchAllocation>> saved = ArgumentCaptor.forClass(List.class);
        verify(allocations).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(SalesDeliveryItemBatchAllocation::getFinishedGoodsBatchId)
                .containsExactly("FG-SAME");
    }

    @Test
    @DisplayName("只剩规格不一致的货: 自动分配报真实原因, 不报「库存不足」让人去补货; 真没货才返回 false")
    void autoAllocationNamesTheRealReason() {
        batch("FG02", "盒", "托盘", "盒", "96", FinishedGoodsBatch.Status.AVAILABLE);

        assertThatThrownBy(() -> service.autoAllocateFifo(FACTORY, "271", PRODUCT, new BigDecimal("1"), "箱", null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo("BATCH_PACKAGING_SPEC_MISMATCH"));
        verify(allocations, never()).saveAll(anyList());

        batches.clear();
        assertThat(service.autoAllocateFifo(FACTORY, "271", PRODUCT, new BigDecimal("1"), "箱", null)).isFalse();
    }

    // ==================== Q10: 换算确认留痕 ====================

    @Test
    @DisplayName("Q10: 确认换算记下谁 (登录身份)、何时、哪张发货行、哪个成品批、从 1托盘=96盒 确认成 1箱=8盒")
    void confirmedConversionLeavesATrail() throws Exception {
        batch("FG02", "盒", "托盘", "盒", "96", FinishedGoodsBatch.Status.AVAILABLE);

        as(WAREHOUSE_MANAGER, allocate("FG02", "1", true)).andExpect(status().isOk());

        assertThat(confirmationRows).hasSize(1);
        SalesPackagingConversionConfirmation row = confirmationRows.get(0);
        assertThat(row.getConfirmedBy()).isEqualTo(WAREHOUSE_MANAGER);
        assertThat(row.getConfirmedAt()).isNotNull();
        assertThat(row.getFactoryId()).isEqualTo(FACTORY);
        assertThat(row.getDeliveryItemId()).isEqualTo("271");
        assertThat(row.getFinishedGoodsBatchId()).isEqualTo("FG02");
        assertThat(row.getBatchNumber()).isEqualTo("QA-FG02");
        assertThat(row.getAllocatedQty()).isEqualByComparingTo("1");
        assertThat(row.getUnit()).isEqualTo("箱");
        assertThat(List.of(row.getFromPackagingUnit(), row.getFromPackagingBaseUnit())).containsExactly("托盘", "盒");
        assertThat(row.getFromPackagingFactor()).isEqualByComparingTo("96");
        assertThat(List.of(row.getToPackagingUnit(), row.getToPackagingBaseUnit())).containsExactly("箱", "盒");
        assertThat(row.getToPackagingFactor()).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("Q10: 「谁」只取登录身份 —— 请求体里塞 confirmedBy 不起作用")
    void confirmerComesFromTheLoginNotTheBody() throws Exception {
        batch("FG02", "盒", "托盘", "盒", "96", FinishedGoodsBatch.Status.AVAILABLE);
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("finishedGoodsBatchId", "FG02");
        row.put("allocatedQty", new BigDecimal("1"));
        row.put("packagingConversionConfirmed", true);
        row.put("confirmedBy", 999);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("allocations", List.of(row));
        body.put("confirmedBy", 999);
        body.put("userId", 999);

        as(SALES, post(BASE).contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(body))).andExpect(status().isOk());

        assertThat(confirmationRows).extracting(SalesPackagingConversionConfirmation::getConfirmedBy).containsExactly(SALES);
    }

    @Test
    @DisplayName("Q10: 同一份分配重放不重复留痕 (幂等); 改了数量是新的一次确认, 再记一行")
    @SuppressWarnings("unchecked")
    void replayDoesNotWriteTheTrailTwice() throws Exception {
        batch("FG02", "盒", "托盘", "盒", "96", FinishedGoodsBatch.Status.AVAILABLE);
        line.setDeliveredQuantity(new BigDecimal("5"));
        as(WAREHOUSE_MANAGER, allocate("FG02", "1", true)).andExpect(status().isOk());
        ArgumentCaptor<List<SalesDeliveryItemBatchAllocation>> saved = ArgumentCaptor.forClass(List.class);
        verify(allocations).saveAll(saved.capture());
        when(allocations.findByFactoryIdAndDeliveryItemId(FACTORY, "271")).thenReturn(saved.getValue());

        as(WAREHOUSE_MANAGER, allocate("FG02", "1", true)).andExpect(status().isOk());
        assertThat(confirmationRows).hasSize(1);

        as(WAREHOUSE_MANAGER, allocate("FG02", "2", true)).andExpect(status().isOk());
        assertThat(confirmationRows).hasSize(2);
        assertThat(confirmationRows.get(1).getAllocatedQty()).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("Q10 阴性: 规格一致的批次带了确认标记也不留痕 (没有换算可确认)")
    void sameSpecLeavesNoTrail() throws Exception {
        batch("FG-SAME", "盒", "箱", "盒", "8", FinishedGoodsBatch.Status.AVAILABLE);
        as(SALES, allocate("FG-SAME", "1", true)).andExpect(status().isOk());
        verifyNoInteractions(confirmations);
    }

    @Test
    @DisplayName("Q10: 不带登录身份的分配入口拒绝换算确认 (留不了「谁」就不能确认), 什么都不写")
    void confirmationWithoutLoginIsRefused() {
        batch("FG02", "盒", "托盘", "盒", "96", FinishedGoodsBatch.Status.AVAILABLE);
        BatchAllocationDTO dto = new BatchAllocationDTO();
        dto.setFinishedGoodsBatchId("FG02");
        dto.setAllocatedQty(new BigDecimal("1"));
        dto.setPackagingConversionConfirmed(true);

        assertThatThrownBy(() -> service.allocateBatches(FACTORY, "271", List.of(dto)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo("PACKAGING_CONFIRMATION_ACTOR_REQUIRED"));
        verify(allocations, never()).saveAll(anyList());
        verifyNoInteractions(confirmations);
    }

    // ==================== Q3: 子发运预填按值返回原因 ====================

    @Test
    @DisplayName("Q3: 子发运预填只剩规格不一致的货 —— 不抛异常, 带回 BATCH_PACKAGING_SPEC_MISMATCH 和两边规格, 不写分配")
    void prefillReturnsTheMismatchInsteadOfThrowing() {
        batch("FG02", "盒", "托盘", "盒", "96", FinishedGoodsBatch.Status.AVAILABLE);

        AutoAllocationOutcome outcome = service.prefillFifo(FACTORY, "271", PRODUCT, new BigDecimal("1"), "箱", null);

        assertThat(outcome.allocated()).isFalse();
        assertThat(outcome.code()).isEqualTo("BATCH_PACKAGING_SPEC_MISMATCH");
        assertThat(outcome.message()).contains("1托盘=96盒").contains("1箱=8盒");
        assertThat(outcome.hint()).contains("换算");
        verify(allocations, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("Q3: 真没货时说库存不够 (FIFO_STOCK_SHORTFALL, 带中文单位); 规格一致的货照常预填")
    void prefillNamesShortfallAndStillAllocatesMatchingStock() {
        AutoAllocationOutcome empty = service.prefillFifo(FACTORY, "271", PRODUCT, new BigDecimal("1"), "箱", null);
        assertThat(empty.allocated()).isFalse();
        assertThat(empty.code()).isEqualTo(AutoAllocationOutcome.CODE_STOCK_SHORTFALL);
        assertThat(empty.message()).contains("1箱");

        batch("FG-SAME", "盒", "箱", "盒", "8", FinishedGoodsBatch.Status.AVAILABLE);
        AutoAllocationOutcome ok = service.prefillFifo(FACTORY, "271", PRODUCT, new BigDecimal("1"), "箱", null);
        assertThat(ok.allocated()).isTrue();
        verify(allocations).saveAll(anyList());
    }

    // ==================== 夹具 ====================

    private MockHttpServletRequestBuilder allocate(String batchId, String qty, Boolean confirmed) throws Exception {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("finishedGoodsBatchId", batchId);
        row.put("allocatedQty", new BigDecimal(qty));
        if (confirmed != null) row.put("packagingConversionConfirmed", confirmed);
        return post(BASE).contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(Map.of("allocations", List.of(row))));
    }

    private ResultActions as(long userId, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.requestAttr("userId", userId));
    }

    private static User user(Long id, String roleCode) {
        User user = new User();
        user.setId(id);
        user.setFactoryId(FACTORY);
        user.setUsername("u-" + roleCode);
        user.setRoleCode(roleCode);
        return user;
    }

    private static SalesDeliveryItem line(String unit, String packagingUnit, String baseUnit, BigDecimal factor) {
        SalesDeliveryRecord record = new SalesDeliveryRecord();
        record.setFactoryId(FACTORY);
        record.setStatus(SalesDeliveryStatus.DRAFT);
        SalesDeliveryItem item = new SalesDeliveryItem();
        item.setId(271L);
        item.setDeliveryRecord(record);
        item.setProductTypeId(PRODUCT);
        item.setDeliveredQuantity(new BigDecimal("12"));
        item.setUnit(unit);
        item.setPackagingUnit(packagingUnit);
        item.setPackagingBaseUnit(baseUnit);
        item.setPackagingFactor(factor);
        return item;
    }

    private FinishedGoodsBatch batch(String id, String unit, String packagingUnit, String baseUnit, String factor,
                                     String status) {
        FinishedGoodsBatch row = new FinishedGoodsBatch();
        row.setId(id);
        row.setFactoryId(FACTORY);
        row.setBatchNumber("QA-" + id);
        row.setProductTypeId(PRODUCT);
        row.setProducedQuantity(new BigDecimal("96"));
        row.setShippedQuantity(BigDecimal.ZERO);
        row.setReservedQuantity(BigDecimal.ZERO);
        row.setUnit(unit);
        row.setPackagingUnit(packagingUnit);
        row.setPackagingBaseUnit(baseUnit);
        row.setPackagingFactor(factor == null ? null : new BigDecimal(factor));
        row.setStatus(status);
        row.setExpireDate(LocalDate.now().plusDays(30));
        batches.add(row);
        return row;
    }
}
