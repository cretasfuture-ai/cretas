package com.cretas.aims.controller.purchase;

import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.SupplierMaterial;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.enums.InquiryQuoteStatus;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.enums.TaxRate;
import com.cretas.aims.entity.enums.TaxTreatment;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.entity.purchase.InquiryQuote;
import com.cretas.aims.entity.purchase.InquiryQuoteSupplierPrice;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierMaterialPurchaseSpecRepository;
import com.cretas.aims.repository.SupplierMaterialRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.purchase.InquiryQuoteRepository;
import com.cretas.aims.repository.purchase.InquiryQuoteSupplierPriceRepository;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.inventory.PurchaseService;
import com.cretas.aims.service.purchase.impl.InquiryQuoteServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L3 报价可比 (S5-01 / S5-02 / S5-04) —— 走真实 HTTP 入口
 * ({@link InquiryQuoteController} → {@link InquiryQuoteServiceImpl}), 仓储为内存桩。
 *
 * <p>守的行为:
 * <ul>
 *   <li>币种 / 单位 / 规格 / 税率 未知或不同 → 不可比, 有原因, 不排序 (低价也不能排在前面)</li>
 *   <li>不填税率存 NULL, ⛔ 不变成 0</li>
 *   <li>过期 / 未生效 / 低于起订量的报价不能选定 (HTTP 入口 409, 不建采购单)</li>
 *   <li>起订量与交期从供应关系读出来并参与判定</li>
 *   <li>挂了采购草稿的核价单: 选定回填那张草稿, ⛔ 不调 createPurchaseOrder</li>
 *   <li>BOM 影响: 无生效日 422; 有生效日给估算, 不回写 BOM</li>
 * </ul>
 */
@DisplayName("核价单比价 — 真实 HTTP 入口契约")
class InquiryQuoteComparabilityEntryContractTest {

    private static final String F = "F006";
    private static final String INQ = "IQ-1";
    private static final String MAT = "MAT-1";
    private static final String BASE = "/api/mobile/" + F + "/purchase/inquiry-quotes/" + INQ;

    private final InquiryQuoteRepository inquiryRepo = mock(InquiryQuoteRepository.class);
    private final InquiryQuoteSupplierPriceRepository priceRepo = mock(InquiryQuoteSupplierPriceRepository.class);
    private final SupplierRepository supplierRepo = mock(SupplierRepository.class);
    private final PurchaseOrderRepository poRepo = mock(PurchaseOrderRepository.class);
    private final PurchaseService purchaseService = mock(PurchaseService.class);
    private final RawMaterialTypeRepository materialRepo = mock(RawMaterialTypeRepository.class);
    private final SupplierMaterialRepository relationRepo = mock(SupplierMaterialRepository.class);
    private final SupplierMaterialPurchaseSpecRepository specRepo = mock(SupplierMaterialPurchaseSpecRepository.class);
    private final BomRecipeItemRepository bomItemRepo = mock(BomRecipeItemRepository.class);
    private final BomRecipeRepository bomRecipeRepo = mock(BomRecipeRepository.class);
    private final com.cretas.aims.service.unit.UnitContractService unitContract =
            mock(com.cretas.aims.service.unit.UnitContractService.class);
    private final MobileService mobileService = mock(MobileService.class);

    private MockMvc mvc;
    private InquiryQuote inquiry;
    private final List<InquiryQuoteSupplierPrice> prices = new ArrayList<>();

    @BeforeEach
    void setUp() {
        InquiryQuoteServiceImpl service = new InquiryQuoteServiceImpl(inquiryRepo, priceRepo, supplierRepo, poRepo,
                purchaseService, materialRepo, relationRepo, specRepo, bomItemRepo, bomRecipeRepo, unitContract);
        mvc = MockMvcBuilders.standaloneSetup(new InquiryQuoteController(service, mobileService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        inquiry = new InquiryQuote();
        inquiry.setId(INQ);
        inquiry.setFactoryId(F);
        inquiry.setInquiryNumber("INQ-20261028-0001");
        inquiry.setMaterialTypeId(MAT);
        inquiry.setMaterialName("辣椒");
        inquiry.setSpecification("特级");
        inquiry.setQuantity(new BigDecimal("100"));
        inquiry.setUnit("kg");
        inquiry.setInquiryDate(LocalDate.now());
        inquiry.setStatus(InquiryQuoteStatus.QUOTED);
        when(inquiryRepo.findById(INQ)).thenReturn(Optional.of(inquiry));
        when(inquiryRepo.save(any(InquiryQuote.class))).thenAnswer(i -> i.getArgument(0));

        RawMaterialType material = new RawMaterialType();
        material.setId(MAT);
        material.setFactoryId(F);
        material.setName("辣椒");
        material.setUnit("kg");
        material.setTaxTreatment(TaxTreatment.TAXABLE);
        material.setTaxRate(TaxRate.TAX_13);
        when(materialRepo.findById(MAT)).thenReturn(Optional.of(material));

        when(priceRepo.findByInquiryQuoteIdOrderByUnitPriceAsc(INQ)).thenAnswer(i -> new ArrayList<>(prices));
        when(priceRepo.findByInquiryQuoteIdAndSupplierId(eq(INQ), anyString())).thenAnswer(i -> prices.stream()
                .filter(p -> p.getSupplierId().equals(i.getArgument(1))).findFirst());
        when(priceRepo.findById(anyString())).thenAnswer(i -> prices.stream()
                .filter(p -> p.getId().equals(i.getArgument(0))).findFirst());
        when(priceRepo.save(any(InquiryQuoteSupplierPrice.class))).thenAnswer(i -> i.getArgument(0));
        when(specRepo.findByFactoryIdAndSupplierMaterialIdAndActiveTrue(eq(F), anyString())).thenReturn(List.of());
        when(poRepo.save(any(PurchaseOrder.class))).thenAnswer(i -> i.getArgument(0));

        // 单位契约桩: 只认 kg 族 (kg / KG / 公斤) 与 箱, 其余未登记 → 不相同
        when(unitContract.areEquivalent(eq(F), anyString(), anyString())).thenAnswer(i ->
                unitCode(i.getArgument(1)) != null
                        && unitCode(i.getArgument(1)).equals(unitCode(i.getArgument(2))));

        UserDTO user = new UserDTO();
        user.setId(7L);
        when(mobileService.getUserFromToken(anyString())).thenReturn(user);
    }

    // ==================== 比价: 不可比不排序 ====================

    @Test
    @DisplayName("只有同口径报价参与排序; 缺税率 / 外币 / 别的单位 / 别的规格的更低价都不可比、不排序")
    void comparisonRanksOnlyComparableQuotes() throws Exception {
        supplier("S-OK", true, relation("S-OK", null, null));
        supplier("S-NOTAX", true, relation("S-NOTAX", null, null));
        supplier("S-USD", true, relation("S-USD", null, null));
        supplier("S-BOX", true, relation("S-BOX", null, null));
        supplier("S-SPEC", true, relation("S-SPEC", null, null));
        supplier("S-OK2", true, relation("S-OK2", null, null));
        prices.add(price("P-OK", "S-OK", "10", "13", "CNY", "kg", "特级"));
        prices.add(price("P-OK2", "S-OK2", "11", "13", "CNY", "kg", "特级"));
        prices.add(price("P-NOTAX", "S-NOTAX", "8", null, "CNY", "kg", "特级"));
        prices.add(price("P-USD", "S-USD", "5", "13", "USD", "kg", "特级"));
        prices.add(price("P-BOX", "S-BOX", "6", "13", "CNY", "箱", "特级"));
        prices.add(price("P-SPEC", "S-SPEC", "7", "13", "CNY", "kg", "统货"));

        mvc.perform(get(BASE + "/comparison"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.comparableCount").value(2))
                .andExpect(jsonPath("$.data.referenceTaxRate").value(13))
                // 排序前两位只能是同口径的 10 和 11, 即使有 5/6/7/8 的更低报价
                .andExpect(jsonPath("$.data.rows[0].priceId").value("P-OK"))
                .andExpect(jsonPath("$.data.rows[0].rank").value(1))
                .andExpect(jsonPath("$.data.rows[1].priceId").value("P-OK2"))
                .andExpect(jsonPath("$.data.rows[1].rank").value(2))
                .andExpect(jsonPath("$.data.rows[?(@.comparable == false)].priceId",
                        containsInAnyOrder("P-NOTAX", "P-USD", "P-BOX", "P-SPEC")))
                .andExpect(jsonPath("$.data.rows[?(@.comparable == false)].rank",
                        contains(nullValue(), nullValue(), nullValue(), nullValue())))
                .andExpect(jsonPath("$.data.rows[?(@.priceId == 'P-NOTAX')].notComparableReasons[*].code",
                        contains("TAX_UNKNOWN")))
                .andExpect(jsonPath("$.data.rows[?(@.priceId == 'P-NOTAX')].taxInclusiveUnitPrice",
                        contains(nullValue())))
                .andExpect(jsonPath("$.data.rows[?(@.priceId == 'P-USD')].notComparableReasons[*].code",
                        contains("CURRENCY_MISMATCH")))
                .andExpect(jsonPath("$.data.rows[?(@.priceId == 'P-BOX')].notComparableReasons[*].code",
                        contains("UNIT_MISMATCH")))
                .andExpect(jsonPath("$.data.rows[?(@.priceId == 'P-SPEC')].notComparableReasons[*].code",
                        contains("SPEC_MISMATCH")));
    }

    @Test
    @DisplayName("报价税率与物料税率不同 → TAX_MISMATCH 不可比")
    void differentTaxRateIsNotComparable() throws Exception {
        supplier("S-9", true, relation("S-9", null, null));
        prices.add(price("P-9", "S-9", "9", "9", "CNY", "kg", "特级"));

        mvc.perform(get(BASE + "/comparison"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].comparable").value(false))
                .andExpect(jsonPath("$.data.rows[0].notComparableReasons[0].code").value("TAX_MISMATCH"))
                .andExpect(jsonPath("$.data.rows[0].rank").value(nullValue()));
    }

    // ==================== 税率不默认 0 ====================

    @Test
    @DisplayName("POST 报价不填税率 → 存 NULL, 不是 0; 币种 / 单位 / 起订量 / 生效日照存")
    void addingQuoteWithoutTaxKeepsTaxUnknown() throws Exception {
        supplier("S-OK", true, relation("S-OK", null, null));
        when(priceRepo.findByInquiryQuoteIdAndSupplierId(INQ, "S-OK")).thenReturn(Optional.empty());

        mvc.perform(post(BASE + "/supplier-prices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierId\":\"S-OK\",\"unitPrice\":10,\"currency\":\"cny\","
                                + "\"quotedUnit\":\"kg\",\"specification\":\"特级\",\"minOrderQuantity\":50,"
                                + "\"priceEffectiveDate\":\"" + LocalDate.now() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taxRate").value(nullValue()))
                .andExpect(jsonPath("$.data.currency").value("CNY"))
                .andExpect(jsonPath("$.data.quotedUnit").value("kg"))
                .andExpect(jsonPath("$.data.minOrderQuantity").value(50));
    }

    // ==================== 有效期 / 生效日 / 起订量 ====================

    @Test
    @DisplayName("过期报价: 比价不排序; 选定入口 409 且不建采购单")
    void expiredQuoteCannotBeSelected() throws Exception {
        supplier("S-OK", true, relation("S-OK", null, null));
        InquiryQuoteSupplierPrice expired = price("P-OLD", "S-OK", "10", "13", "CNY", "kg", "特级");
        expired.setValidUntil(LocalDate.now().minusDays(1));
        prices.add(expired);

        mvc.perform(get(BASE + "/comparison"))
                .andExpect(jsonPath("$.data.rows[0].comparable").value(true))
                .andExpect(jsonPath("$.data.rows[0].rank").value(nullValue()))
                .andExpect(jsonPath("$.data.rows[0].selectable").value(false))
                .andExpect(jsonPath("$.data.rows[0].blockingReasons[*].code", hasItem("QUOTE_EXPIRED")));

        mvc.perform(post(BASE + "/select-and-convert")
                        .header("Authorization", "Bearer t")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selectedSupplierId\":\"S-OK\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("过期")));
        verify(purchaseService, never()).createPurchaseOrder(anyString(), any(), anyLong());
        verify(purchaseService, never()).applyQuoteToDraftLine(anyString(), anyString(), anyString(), any(), any(), anyString(),
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("税率未知的报价: 选定入口 409, 不会按 0% 或物料税率下单")
    void taxUnknownQuoteCannotBeSelected() throws Exception {
        supplier("S-NOTAX", true, relation("S-NOTAX", null, null));
        prices.add(price("P-NOTAX", "S-NOTAX", "8", null, "CNY", "kg", "特级"));

        mvc.perform(post(BASE + "/select-and-convert")
                        .header("Authorization", "Bearer t")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selectedSupplierId\":\"S-NOTAX\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("税率未知")));
        verify(purchaseService, never()).createPurchaseOrder(anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("价格未到生效日: 不能选定")
    void notYetEffectivePriceCannotBeSelected() throws Exception {
        supplier("S-OK", true, relation("S-OK", null, null));
        InquiryQuoteSupplierPrice future = price("P-FUT", "S-OK", "10", "13", "CNY", "kg", "特级");
        future.setPriceEffectiveDate(LocalDate.now().plusDays(3));
        prices.add(future);

        mvc.perform(get(BASE + "/comparison"))
                .andExpect(jsonPath("$.data.rows[0].selectable").value(false))
                .andExpect(jsonPath("$.data.rows[0].blockingReasons[*].code", hasItem("PRICE_NOT_YET_EFFECTIVE")));
    }

    @Test
    @DisplayName("起订量 / 交期从供应关系读出: 询价量低于起订量不能选定; 交期晚于需求日给提示")
    void moqAndLeadTimeAreReadFromSupplierMaterial() throws Exception {
        inquiry.setRequiredDate(LocalDate.now().plusDays(3));
        supplier("S-OK", true, relation("S-OK", new BigDecimal("200"), 7));
        prices.add(price("P-OK", "S-OK", "10", "13", "CNY", "kg", "特级"));

        mvc.perform(get(BASE + "/comparison"))
                .andExpect(jsonPath("$.data.rows[0].minOrderQuantity").value(200))
                .andExpect(jsonPath("$.data.rows[0].minOrderQuantitySource").value("SUPPLIER_MATERIAL"))
                .andExpect(jsonPath("$.data.rows[0].leadTimeDays").value(7))
                .andExpect(jsonPath("$.data.rows[0].leadTimeSource").value("SUPPLIER_MATERIAL"))
                .andExpect(jsonPath("$.data.rows[0].selectable").value(false))
                .andExpect(jsonPath("$.data.rows[0].blockingReasons[*].code", contains("BELOW_MOQ")))
                .andExpect(jsonPath("$.data.rows[0].warnings[*].code", hasItem("LEAD_TIME_LATE")));
    }

    @Test
    @DisplayName("报价自带起订量优先于供应关系")
    void quoteMoqOverridesRelationMoq() throws Exception {
        supplier("S-OK", true, relation("S-OK", new BigDecimal("200"), null));
        InquiryQuoteSupplierPrice p = price("P-OK", "S-OK", "10", "13", "CNY", "kg", "特级");
        p.setMinOrderQuantity(new BigDecimal("50"));
        prices.add(p);

        mvc.perform(get(BASE + "/comparison"))
                .andExpect(jsonPath("$.data.rows[0].minOrderQuantity").value(50))
                .andExpect(jsonPath("$.data.rows[0].minOrderQuantitySource").value("QUOTE"))
                .andExpect(jsonPath("$.data.rows[0].selectable").value(true));
    }

    // ==================== 回填已有草稿 ====================

    @Test
    @DisplayName("挂了采购草稿: 选定回填那张草稿, 不调 createPurchaseOrder")
    void selectionFillsExistingDraftInsteadOfCreatingOne() throws Exception {
        inquiry.setSourcePurchaseOrderId("PO-DRAFT");
        supplier("S-OK", true, relation("S-OK", null, null));
        prices.add(price("P-OK", "S-OK", "10", "13", "CNY", "kg", "特级"));
        PurchaseOrder draft = new PurchaseOrder();
        draft.setId("PO-DRAFT");
        draft.setOrderNumber("PO-20261028-0001");
        draft.setFactoryId(F);
        draft.setStatus(PurchaseOrderStatus.DRAFT);
        when(purchaseService.applyQuoteToDraftLine(eq(F), eq("PO-DRAFT"), eq(MAT), eq("kg"), eq(new BigDecimal("100")), eq("S-OK"),
                any(), eq("kg"), any(), isNull())).thenReturn(draft);

        mvc.perform(post(BASE + "/select-and-convert")
                        .header("Authorization", "Bearer t")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selectedSupplierId\":\"S-OK\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("PO-DRAFT"))
                .andExpect(jsonPath("$.data.inquiryQuoteId").value(INQ));

        verify(purchaseService).applyQuoteToDraftLine(eq(F), eq("PO-DRAFT"), eq(MAT), eq("kg"), eq(new BigDecimal("100")), eq("S-OK"),
                eq(new BigDecimal("10")), eq("kg"), eq(new BigDecimal("13")), isNull());
        verify(purchaseService, never()).createPurchaseOrder(anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("创建核价单挂草稿: 同一草稿行已有进行中的核价单 → 409")
    void secondOpenInquiryOnSameDraftLineIsRejected() throws Exception {
        stubDraftWithLine("100");
        when(inquiryRepo.findByFactoryIdAndSourcePurchaseOrderIdAndMaterialTypeIdAndStatusIn(
                eq(F), eq("PO-DRAFT"), eq(MAT), any())).thenReturn(List.of(inquiry));

        mvc.perform(createFromDraft("100"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("INQ-20261028-0001")));
        verify(inquiryRepo, never()).save(any(InquiryQuote.class));
        verify(inquiryRepo, never()).saveAndFlush(any(InquiryQuote.class));
    }

    @Test
    @DisplayName("并发创建挂同一草稿行: 先查都过了, 唯一索引兜底 → 干净的 409, 不是 500")
    void concurrentCreateOnSameDraftLineLoserGetsConflict() throws Exception {
        stubDraftWithLine("100");
        when(inquiryRepo.findByFactoryIdAndSourcePurchaseOrderIdAndMaterialTypeIdAndStatusIn(
                eq(F), eq("PO-DRAFT"), eq(MAT), any())).thenReturn(List.of());
        when(inquiryRepo.findMaxInquiryNumberByPrefix(eq(F), any())).thenReturn(Optional.empty());
        when(inquiryRepo.saveAndFlush(any(InquiryQuote.class))).thenThrow(
                new org.springframework.dao.DataIntegrityViolationException("insert failed",
                        new RuntimeException("ERROR: duplicate key value violates unique constraint "
                                + "\"uk_iq_open_source_draft_line\"")));

        mvc.perform(createFromDraft("100"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("请勿重复发起")));
    }

    @Test
    @DisplayName("挂草稿创建: 询价数量与草稿行不同 → 400 (回填只改单价, 数量以草稿为准)")
    void createFromDraftRequiresTheDraftLineQuantity() throws Exception {
        stubDraftWithLine("80");

        mvc.perform(createFromDraft("100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("数量以草稿为准")));
        verify(inquiryRepo, never()).saveAndFlush(any(InquiryQuote.class));
    }

    private void stubDraftWithLine(String lineQuantity) {
        PurchaseOrder draft = new PurchaseOrder();
        draft.setId("PO-DRAFT");
        draft.setOrderNumber("PO-20261028-0001");
        draft.setFactoryId(F);
        draft.setStatus(PurchaseOrderStatus.DRAFT);
        PurchaseOrderItem line = new PurchaseOrderItem();
        line.setMaterialTypeId(MAT);
        line.setUnit("kg");
        line.setQuantity(new BigDecimal(lineQuantity));
        draft.setItems(List.of(line));
        when(purchaseService.getPurchaseOrderById(F, "PO-DRAFT")).thenReturn(draft);
        when(inquiryRepo.findRecentDuplicates(any(), any(), any(), any(), any())).thenReturn(List.of());
    }

    private static org.springframework.test.web.servlet.RequestBuilder createFromDraft(String quantity) {
        return post("/api/mobile/" + F + "/purchase/inquiry-quotes")
                .header("Authorization", "Bearer t")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"materialTypeId\":\"" + MAT + "\",\"quantity\":" + quantity + ",\"unit\":\"kg\","
                        + "\"inquiryDate\":\"" + LocalDate.now() + "\",\"sourcePurchaseOrderId\":\"PO-DRAFT\"}");
    }

    // ==================== BOM 影响 (S5-04) ====================

    @Test
    @DisplayName("BOM 影响: 无价格生效日 → 422")
    void priceImpactRequiresEffectiveDate() throws Exception {
        prices.add(price("P-OK", "S-OK", "10", "13", "CNY", "kg", "特级"));

        mvc.perform(get(BASE + "/supplier-prices/P-OK/bom-impact"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("生效日")));
    }

    @Test
    @DisplayName("BOM 影响: 按报价未税价估算行成本, 单位不同的行不可算, 不回写 BOM")
    void priceImpactEstimatesWithoutWritingBom() throws Exception {
        supplier("S-OK", true, relation("S-OK", null, null));
        InquiryQuoteSupplierPrice p = price("P-OK", "S-OK", "12", "13", "CNY", "kg", "特级");
        p.setPriceEffectiveDate(LocalDate.now().plusDays(10));
        prices.add(p);

        BomRecipe recipe = new BomRecipe();
        recipe.setId("R-1");
        recipe.setFactoryId(F);
        recipe.setRecipeCode("BOM-1");
        recipe.setProductName("辣酱");
        recipe.setVersion(3);
        recipe.setStatus(BomRecipe.Status.ACTIVE);
        BomRecipeItem sameUnit = bomItem(1L, "kg", "2", "10");
        BomRecipeItem otherUnit = bomItem(2L, "g", "500", "0.01");
        when(bomItemRepo.findByFactoryIdAndMaterialTypeId(F, MAT)).thenReturn(List.of(sameUnit, otherUnit));
        when(bomRecipeRepo.findAllById(any())).thenReturn(List.of(recipe));

        mvc.perform(get(BASE + "/supplier-prices/P-OK/bom-impact"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nature").value("ESTIMATE"))
                .andExpect(jsonPath("$.data.historicalCostsModified").value(false))
                .andExpect(jsonPath("$.data.affectedRecipeCount").value(1))
                .andExpect(jsonPath("$.data.rows[0].recipeVersion").value(3))
                .andExpect(jsonPath("$.data.rows[0].computable").value(true))
                .andExpect(jsonPath("$.data.rows[0].currentItemCost").value(20))
                .andExpect(jsonPath("$.data.rows[0].estimatedItemCost").value(24))
                .andExpect(jsonPath("$.data.rows[0].deltaItemCost").value(4))
                .andExpect(jsonPath("$.data.rows[1].computable").value(false))
                .andExpect(jsonPath("$.data.rows[1].estimatedItemCost").value(nullValue()));

        verify(bomItemRepo, never()).save(any());
        verify(bomItemRepo, never()).saveAll(any());
        org.assertj.core.api.Assertions.assertThat(sameUnit.getUnitPrice()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("单位走单位契约: 报价写「公斤」与询价「kg」是同一单位, 可比")
    void unitIdentityGoesThroughTheUnitContract() throws Exception {
        supplier("S-OK", true, relation("S-OK", null, null));
        prices.add(price("P-JIN", "S-OK", "10", "13", "CNY", "公斤", "特级"));

        mvc.perform(get(BASE + "/comparison"))
                .andExpect(jsonPath("$.data.rows[0].comparable").value(true))
                .andExpect(jsonPath("$.data.rows[0].rank").value(1));
    }

    // ==================== fixtures ====================

    private static String unitCode(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase();
        if (v.equals("kg") || v.equals("公斤")) return "kg";
        if (v.equals("箱")) return "case";
        return null;
    }

    private void supplier(String id, boolean active, SupplierMaterial relation) {
        Supplier s = new Supplier();
        s.setId(id);
        s.setFactoryId(F);
        s.setName("供应商" + id);
        s.setIsActive(active);
        when(supplierRepo.findByIdAndFactoryId(id, F)).thenReturn(Optional.of(s));
        when(relationRepo.findByFactoryIdAndSupplierIdAndMaterialTypeId(F, id, MAT))
                .thenReturn(Optional.ofNullable(relation));
    }

    private static SupplierMaterial relation(String supplierId, BigDecimal moq, Integer leadDays) {
        SupplierMaterial r = new SupplierMaterial();
        r.setId("REL-" + supplierId);
        r.setFactoryId(F);
        r.setSupplierId(supplierId);
        r.setMaterialTypeId(MAT);
        r.setPurchaseUnit("kg");
        r.setMinOrderQuantity(moq);
        r.setLeadTimeDays(leadDays);
        r.setActive(true);
        return r;
    }

    private static InquiryQuoteSupplierPrice price(String id, String supplierId, String unitPrice, String tax,
                                                   String currency, String unit, String spec) {
        InquiryQuoteSupplierPrice p = new InquiryQuoteSupplierPrice();
        p.setId(id);
        p.setInquiryQuoteId(INQ);
        p.setSupplierId(supplierId);
        p.setUnitPrice(new BigDecimal(unitPrice));
        p.setTaxRate(tax == null ? null : new BigDecimal(tax));
        p.setCurrency(currency);
        p.setQuotedUnit(unit);
        p.setSpecification(spec);
        p.setValidUntil(LocalDate.now().plusDays(30));
        return p;
    }

    private static BomRecipeItem bomItem(Long id, String unit, String qty, String unitPrice) {
        BomRecipeItem item = new BomRecipeItem();
        item.setId(id);
        item.setRecipeId("R-1");
        item.setFactoryId(F);
        item.setMaterialTypeId(MAT);
        item.setUnit(unit);
        item.setPriceUnit(unit);
        item.setStandardQuantity(new BigDecimal(qty));
        item.setYieldRate(new BigDecimal("100"));
        item.setUnitPrice(new BigDecimal(unitPrice));
        return item;
    }
}
