package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.dto.material.CreateMaterialBatchRequest;
import com.cretas.aims.dto.material.RawMaterialTypeDTO;
import com.cretas.aims.dto.supplier.SupplierDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository;
import com.cretas.aims.service.FuturePlanMatchingService;
import com.cretas.aims.service.RawMaterialTypeService;
import com.cretas.aims.service.SupplierService;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.impl.MaterialBatchServiceImpl;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.cretas.aims.ai.tool.PreviewToolTestSupport.assertPlainChinese;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.call;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.ctx;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.data;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.injectMapper;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.needMoreInfoQuestions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 原料入库 (material_batch_create) 只读预览。
 *
 * <p>🔴 <b>先读这条</b>: 这个工具 execute 建的 {@link CreateMaterialBatchRequest} 不带来源单据,
 * 真实的 {@code MaterialBatchServiceImpl#validateSourceDoc} 对此一律 400 (F4 守卫: 仓库无单不可建库存)。
 * 所以它的预览 <b>恒为 canDo=false</b> —— 这是预览在说真话, 不是预览写坏了。本类用
 * <b>真实的</b> {@code MaterialBatchServiceImpl} 把这件事钉死 (execute 真的被拒、{@code save} 真的没被调),
 * 并配一条「带上来源单据同一个服务就放行」的对照, 证明拒绝来自缺来源单据而不是夹具坏了。
 */
class MaterialBatchCreatePreviewToolTest {

    private static final String FACTORY = "F006";
    private static final long USER_ID = 42L;
    private static final String MATERIAL_ID = "mat-fish";
    private static final String SUPPLIER_ID = "sup-1";

    private RawMaterialTypeService materialTypeService;
    private SupplierService supplierService;
    private MaterialBatchRepository batchRepo;
    private PurchaseReceiveRecordRepository purchaseReceiveRepo;
    private MaterialBatchServiceImpl realService;
    /** 真实服务外面套一层 spy: 预览期间 createMaterialBatch 一次都不许被调 (即使真实服务会自己拒绝, 「尝试写」本身也算违规)。 */
    private MaterialBatchServiceImpl spiedService;
    private MaterialBatchCreateTool tool;

    @BeforeEach
    void setUp() throws Exception {
        materialTypeService = mock(RawMaterialTypeService.class);
        supplierService = mock(SupplierService.class);
        batchRepo = mock(MaterialBatchRepository.class);
        purchaseReceiveRepo = mock(PurchaseReceiveRecordRepository.class);
        realService = realService();
        spiedService = org.mockito.Mockito.spy(realService);

        tool = new MaterialBatchCreateTool();
        ReflectionTestUtils.setField(tool, "materialBatchService", spiedService);
        ReflectionTestUtils.setField(tool, "rawMaterialTypeService", materialTypeService);
        ReflectionTestUtils.setField(tool, "supplierService", supplierService);
        injectMapper(tool);

        RawMaterialTypeDTO type = new RawMaterialTypeDTO();
        type.setId(MATERIAL_ID);
        type.setName("带鱼");
        when(materialTypeService.getMaterialTypeById(FACTORY, MATERIAL_ID)).thenReturn(type);
        SupplierDTO supplier = new SupplierDTO();
        supplier.setId(SUPPLIER_ID);
        supplier.setName("渔港供应商");
        when(supplierService.getSupplierById(FACTORY, SUPPLIER_ID)).thenReturn(supplier);
    }

    @Test
    @DisplayName("参数齐全 → canDo=false (缺来源单据), 原因是人话, 带原料/供应商/数量名字; 不调 createMaterialBatch")
    void completeParamsAreStillBlockedBecauseTheWritePathCannotSucceed() throws Exception {
        String json = tool.preview(call("material_batch_create", args("入库一批带鱼,数量500公斤")), ctx(FACTORY, USER_ID));
        // 先看有没有「尝试写」, 再读出口: 真实服务会自己拒绝, 出口读数会掩盖「调了写方法」这件事
        verify(spiedService, never()).createMaterialBatch(anyString(), any(), any());
        org.mockito.Mockito.verifyNoInteractions(batchRepo);
        Map<String, Object> d = data(json);

        assertThat(tool.supportsPreview()).isTrue();
        assertThat(d.get("canDo")).isEqualTo(false);
        assertThat(d.get("status")).isEqualTo("SOURCE_DOC_REQUIRED");
        assertThat(d.get("materialName")).isEqualTo("带鱼");
        assertThat(d.get("supplierName")).isEqualTo("渔港供应商");
        assertThat(d.get("quantityText")).isEqualTo("500 公斤");
        String message = String.valueOf(d.get("message"));
        assertThat(message).contains("「带鱼」入库 500 公斤（供应商：渔港供应商）办不了").contains("来源单据")
                .contains("确认收货").doesNotContain(MATERIAL_ID).doesNotContain(SUPPLIER_ID);
        assertPlainChinese(message);
        assertThat(d).as("办不了就不给对比表").doesNotContainKey("newValues");
        verify(batchRepo, never()).save(any(MaterialBatch.class));
    }

    @Test
    @DisplayName("钉死前提: 同样的输入走 execute, 真实服务真的拒 (没有来源单据), save 没被调; 对照: 带上来源单据同一个服务就放行")
    void executeIsReallyRefusedByTheRealServiceAndTheFixtureIsNotJustBroken() throws Exception {
        String exec = tool.execute(call("material_batch_create", args("入库一批带鱼,数量500公斤")), ctx(FACTORY, USER_ID));
        verify(spiedService, org.mockito.Mockito.times(1)).createMaterialBatch(eq(FACTORY), any(), any());   // 必发生: execute 确实尝试写了
        Map<String, Object> inner = data(exec);
        assertThat(inner.get("success")).isEqualTo(false);
        assertThat(String.valueOf(inner.get("message"))).contains("入库失败").contains("来源单据");
        verify(batchRepo, never()).save(any(MaterialBatch.class));

        // 对照: 同一个真实服务, 请求里带 PURCHASE_RECEIVE + 单据 → 放行并落库
        when(purchaseReceiveRepo.existsById("PRR-1")).thenReturn(true);
        CreateMaterialBatchRequest withDoc = new CreateMaterialBatchRequest();
        withDoc.setMaterialTypeId(MATERIAL_ID);
        withDoc.setSupplierId(SUPPLIER_ID);
        withDoc.setReceiptDate(LocalDate.now());
        withDoc.setReceiptQuantity(BigDecimal.valueOf(500));
        withDoc.setQuantityUnit("kg");
        withDoc.setTotalWeight(BigDecimal.valueOf(500));
        withDoc.setTotalValue(BigDecimal.valueOf(5000));
        withDoc.setSourceDocType("PURCHASE_RECEIVE");
        withDoc.setSourceDocId("PRR-1");
        assertThatCode(() -> realService.createMaterialBatch(FACTORY, withDoc, USER_ID)).doesNotThrowAnyException();
        verify(batchRepo).save(any(MaterialBatch.class));
    }

    @Test
    @DisplayName("缺原料 / 缺供应商 / 缺数量 → 各自 NEED_MORE_INFO, 追问是人话且互不相同; 原话里写了数量就不再问数量")
    void missingInfoIsAskedInPlainChinese() throws Exception {
        Map<String, Object> noMaterial = args("入库一批带鱼,数量500公斤");
        noMaterial.remove("materialTypeId");
        Map<String, Object> noSupplier = args("入库一批带鱼,数量500公斤");
        noSupplier.remove("supplierId");
        Map<String, Object> noQuantity = args("入库一批带鱼");   // 原话里没有数量, 参数里也没有
        noQuantity.remove("quantity");

        String q1 = needMoreInfoQuestions(tool.preview(call("material_batch_create", noMaterial), ctx(FACTORY, USER_ID))).get(0);
        String q2 = needMoreInfoQuestions(tool.preview(call("material_batch_create", noSupplier), ctx(FACTORY, USER_ID))).get(0);
        String q3 = needMoreInfoQuestions(tool.preview(call("material_batch_create", noQuantity), ctx(FACTORY, USER_ID))).get(0);
        assertThat(java.util.Set.of(q1, q2, q3)).hasSize(3);
        assertThat(q1).contains("哪种原材料");
        assertThat(q2).contains("哪个供应商");
        assertThat(q3).contains("入库多少");

        // 对照: 数量只在原话里 (参数里没有) —— execute 就是这么取数量的, 预览不能多问一句
        Map<String, Object> quantityOnlyInSentence = args("入库一批带鱼,数量500公斤");
        quantityOnlyInSentence.remove("quantity");
        assertThat(data(tool.preview(call("material_batch_create", quantityOnlyInSentence), ctx(FACTORY, USER_ID)))
                .get("quantityText")).isEqualTo("500 公斤");
        verify(batchRepo, never()).save(any(MaterialBatch.class));
    }

    @Test
    @DisplayName("原料 / 供应商查不到 (或别厂的) → canDo=false + 各自的「找不到」; 数量非正 → canDo=false")
    void unknownMaterialSupplierAndBadQuantity() throws Exception {
        when(materialTypeService.getMaterialTypeById(FACTORY, "mat-x")).thenThrow(new ResourceNotFoundException("原材料类型不存在: mat-x"));
        Map<String, Object> badMaterial = args("入库一批带鱼,数量500公斤");
        badMaterial.put("materialTypeId", "mat-x");
        Map<String, Object> d1 = data(tool.preview(call("material_batch_create", badMaterial), ctx(FACTORY, USER_ID)));
        assertThat(d1.get("status")).isEqualTo("MATERIAL_NOT_FOUND");
        assertThat(String.valueOf(d1.get("message"))).contains("找不到这种原材料").doesNotContain("mat-x");

        when(supplierService.getSupplierById(FACTORY, "sup-x")).thenThrow(new com.cretas.aims.exception.EntityNotFoundException("Supplier", "sup-x"));
        Map<String, Object> badSupplier = args("入库一批带鱼,数量500公斤");
        badSupplier.put("supplierId", "sup-x");
        Map<String, Object> d2 = data(tool.preview(call("material_batch_create", badSupplier), ctx(FACTORY, USER_ID)));
        assertThat(d2.get("status")).isEqualTo("SUPPLIER_NOT_FOUND");
        assertThat(String.valueOf(d2.get("message"))).contains("找不到这个供应商").doesNotContain("sup-x");

        Map<String, Object> zero = args("入库一批带鱼");
        zero.put("quantity", 0);
        Map<String, Object> d3 = data(tool.preview(call("material_batch_create", zero), ctx(FACTORY, USER_ID)));
        assertThat(d3.get("status")).isEqualTo("PREVIEW_INVALID");
        assertThat(String.valueOf(d3.get("message"))).contains("入库数量必须大于 0");

        // 对照: 三项都对时到达的是「缺来源单据」那一条 —— 证明上面三条各自拦在更前面
        assertThat(data(tool.preview(call("material_batch_create", args("入库一批带鱼,数量500公斤")), ctx(FACTORY, USER_ID)))
                .get("status")).isEqualTo("SOURCE_DOC_REQUIRED");
        verify(batchRepo, never()).save(any(MaterialBatch.class));
    }

    // ==================== helpers ====================

    private Map<String, Object> args(String userInput) {
        Map<String, Object> a = new HashMap<>();
        a.put("userInput", userInput);
        a.put("materialTypeId", MATERIAL_ID);
        a.put("supplierId", SUPPLIER_ID);
        a.put("quantity", 500);
        return a;
    }

    /** 与 MaterialBatchServiceImplInboundSourceGuardTest 同一套夹具: 真实服务, 只桩掉 repository / mapper。 */
    private MaterialBatchServiceImpl realService() {
        MaterialBatchServiceImpl service = new MaterialBatchServiceImpl(
                batchRepo,
                mock(MaterialBatchAdjustmentRepository.class),
                mock(RawMaterialTypeRepository.class),
                mock(MaterialBatchMapper.class),
                mock(MaterialConsumptionRepository.class),
                mock(ProductionPlanBatchUsageRepository.class),
                mock(ExcelUtil.class),
                mock(FuturePlanMatchingService.class));
        RawMaterialTypeRepository typeRepo = (RawMaterialTypeRepository) ReflectionTestUtils.getField(service, "materialTypeRepository");
        MaterialBatchMapper mapper = (MaterialBatchMapper) ReflectionTestUtils.getField(service, "materialBatchMapper");
        ReflectionTestUtils.setField(service, "warehouseResolver", mock(WarehouseResolver.class));
        ReflectionTestUtils.setField(service, "purchaseReceiveRecordRepository", purchaseReceiveRepo);
        UserRepository userRepository = mock(UserRepository.class);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);

        RawMaterialType type = new RawMaterialType();
        type.setId(MATERIAL_ID);
        type.setFactoryId(FACTORY);
        type.setName("带鱼");
        type.setUnit("kg");
        when(typeRepo.findById(MATERIAL_ID)).thenReturn(Optional.of(type));
        when(mapper.toEntity(any(CreateMaterialBatchRequest.class), eq(FACTORY), eq(USER_ID))).thenAnswer(inv -> {
            CreateMaterialBatchRequest req = inv.getArgument(0);
            MaterialBatch b = new MaterialBatch();
            b.setFactoryId(FACTORY);
            b.setMaterialTypeId(req.getMaterialTypeId());
            b.setReceiptQuantity(req.getReceiptQuantity());
            b.setQuantityUnit(req.getQuantityUnit());
            b.setReceiptDate(req.getReceiptDate());
            b.setUnitPrice(BigDecimal.TEN);
            b.setSourceDocType(req.getSourceDocType());
            b.setSourceDocId(req.getSourceDocId());
            b.setCreatedBy(USER_ID);
            return b;
        });
        when(batchRepo.save(any(MaterialBatch.class))).thenAnswer(inv -> inv.getArgument(0));
        User user = new User();
        user.setId(USER_ID);
        user.setFactoryId(FACTORY);
        user.setRoleCode("warehouse_worker");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        return service;
    }
}
