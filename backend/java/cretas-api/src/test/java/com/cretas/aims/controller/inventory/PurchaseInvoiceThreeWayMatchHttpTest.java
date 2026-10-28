package com.cretas.aims.controller.inventory;

import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.enums.PurchaseInvoiceStatus;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.enums.PurchaseReceiveStatus;
import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.inventory.PurchaseInvoice;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.ReturnOrderItem;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.inventory.impl.PurchaseInvoiceServiceImpl;
import com.cretas.aims.service.inventory.impl.PurchaseInvoiceThreeWayMatcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * S5-05/06/07 逐行三单匹配 —— 走真实 HTTP 入口（MockMvc → 控制器 → 服务 → JPA 仓库）。
 *
 * <p>判据守的是行为，不是实现：
 * <ul>
 *   <li>S5-05 行级一致才自动相符；表头金额相等但没有行级资料 → 待核对</li>
 *   <li>S5-06 同金额错料 → 不自动相符、行差异可见、人工也不能核成相符；少收按订单量开票 → 409</li>
 *   <li>S5-07 分票累计守恒：有效收货 − 退货 − 已开 = 剩余；超开 409；收货行不能被重复开满；
 *       「不符」的票释放占用</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import({PurchaseInvoiceController.class, PurchaseInvoiceServiceImpl.class, PurchaseInvoiceThreeWayMatcher.class})
class PurchaseInvoiceThreeWayMatchHttpTest {

    private static final String F = "TW-F1";
    private static final String MAT_A = "MAT-A";
    private static final String MAT_B = "MAT-B";

    @Autowired private PurchaseInvoiceController controller;
    @Autowired private EntityManager em;
    @MockBean private MobileService mobileService;

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;
    private int seq;

    @BeforeEach
    void setUp() {
        // 本测试只关心三单账，不建工厂/供应商/用户主数据
        em.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate();
        UserDTO user = new UserDTO();
        user.setId(7L);
        when(mobileService.getUserFromToken(anyString())).thenReturn(user);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ─── S5-05 ────────────────────────────────────────────────────────────────

    @Test
    void s5_05_allLinesConsistent_autoMatchesAndClosesOrder() throws Exception {
        PurchaseOrder po = order();
        PurchaseOrderItem a = line(po, MAT_A, "100", "10", "13");   // 含税 1130.00
        PurchaseOrderItem b = line(po, MAT_B, "50", "20", "0");     // 1000.00
        receipt(po, a, "100");
        receipt(po, b, "50");

        JsonNode inv = upload(po, "2130.00", List.of(
                lineReq(a, null, MAT_A, "100", "1130.00"),
                lineReq(b, null, MAT_B, "50", "1000.00")), 200);

        assertThat(inv.at("/data/reconcileStatus").asText()).isEqualTo("MATCHED");
        em.flush(); em.clear();
        assertThat(em.find(PurchaseOrder.class, po.getId()).getInvoiceStatus())
                .isEqualTo(PurchaseInvoiceStatus.RECONCILED);
        JsonNode status = matchStatus(po);
        assertThat(status.at("/data/fullyMatched").asBoolean()).isTrue();
        assertThat(status.at("/data/lines/0/status").asText()).isEqualTo("INVOICED");
    }

    @Test
    void s5_05_newUploadWithoutLines_isRejectedAndSavesNothing() throws Exception {
        PurchaseOrder po = order();
        PurchaseOrderItem a = line(po, MAT_A, "100", "10", "13");
        receipt(po, a, "100");

        JsonNode refused = upload(po, "1130.00", List.of(), 400);

        assertThat(refused.at("/errorCode").asText()).isEqualTo("PURCHASE_INVOICE_LINES_REQUIRED");
        assertThat(countInvoices(po)).isZero();
        em.clear();
        assertThat(em.find(PurchaseOrder.class, po.getId()).getInvoiceStatus())
                .isEqualTo(PurchaseInvoiceStatus.NOT_RECEIVED);
    }

    @Test
    void manualMatchOfPartialNewInvoice_doesNotReconcileOrder_butLegacyInvoiceKeepsOldRule() throws Exception {
        // 新票: 只开 50 / 100，发票没写物料 → 不自动相符；人工核成相符也只算 50，采购单不闭环
        PurchaseOrder po = order();
        PurchaseOrderItem a = line(po, MAT_A, "100", "10", "0");
        receipt(po, a, "100");
        JsonNode inv = upload(po, "500.00", List.of(lineReq(a, null, null, "50", "500.00")), 200);
        assertThat(inv.at("/data/reconcileStatus").asText()).isEqualTo("PENDING");
        assertThat(inv.at("/data/reconcileNote").asText()).contains("未写物料");
        reconcile(inv.at("/data/id").asText(), "MATCHED", 200);
        em.flush(); em.clear();
        assertThat(em.find(PurchaseOrder.class, po.getId()).getInvoiceStatus())
                .isEqualTo(PurchaseInvoiceStatus.RECEIVED);
        assertThat(matchStatus(po).at("/data/lines/0/remainingQuantity").decimalValue()).isEqualByComparingTo("50");

        // 切换前的历史票 (line_match_required = FALSE): 人工核对相符仍按旧口径闭环
        PurchaseOrder legacyPo = order();
        PurchaseOrderItem b = line(legacyPo, MAT_A, "100", "10", "0");
        receipt(legacyPo, b, "100");
        PurchaseInvoice legacy = new PurchaseInvoice();
        legacy.setFactoryId(F);
        legacy.setPurchaseOrderId(legacyPo.getId());
        legacy.setSupplierId(legacyPo.getSupplierId());
        legacy.setInvoiceNumber("FP-LEGACY-" + (++seq));
        legacy.setAmount(new BigDecimal("1000.00"));
        legacy.setReconcileStatus("PENDING");
        legacy.setUploadedBy(7L);
        em.persist(legacy);
        em.flush();
        assertThat(legacy.getLineMatchRequired()).isFalse();
        reconcile(legacy.getId(), "MATCHED", 200);
        em.flush(); em.clear();
        assertThat(em.find(PurchaseOrder.class, legacyPo.getId()).getInvoiceStatus())
                .isEqualTo(PurchaseInvoiceStatus.RECONCILED);
    }

    @Test
    void overReceiptBeyondOrderedQuantity_isNotInvoiceable() throws Exception {
        PurchaseOrder po = order();
        PurchaseOrderItem a = line(po, MAT_A, "100", "10", "0");
        receipt(po, a, "110");   // 超收 10

        JsonNode refused = upload(po, "1050.00", List.of(lineReq(a, null, MAT_A, "105", "1050.00")), 409);
        assertThat(refused.at("/message").asText()).contains("最多还可开 100");
    }

    @Test
    void unknownTaxRate_isPriceUnknownNotAutoMatched() throws Exception {
        PurchaseOrder po = order();
        PurchaseOrderItem a = line(po, MAT_A, "100", "10", "0");
        a.setTaxRate(null);
        receipt(po, a, "100");

        JsonNode inv = upload(po, "1000.00", List.of(lineReq(a, null, MAT_A, "100", "1000.00")), 200);

        assertThat(inv.at("/data/reconcileStatus").asText()).isEqualTo("PENDING");
        assertThat(inv.at("/data/reconcileNote").asText()).contains("税率未知");
    }

    @Test
    void overInvoiceMessageUsesChineseUnitName() throws Exception {
        PurchaseOrder po = order();
        PurchaseOrderItem a = line(po, MAT_A, "10", "10", "0");
        a.setUnit("box");
        receipt(po, a, "3");

        JsonNode refused = upload(po, "40.00", List.of(lineReq(a, null, MAT_A, "4", "40.00")), 409);
        assertThat(refused.at("/message").asText()).doesNotContain("box");
    }

    // ─── S5-06 ────────────────────────────────────────────────────────────────

    @Test
    void s5_06_sameAmountWrongMaterial_isNotMatchedAndManualMatchIsRefused() throws Exception {
        PurchaseOrder po = order();
        PurchaseOrderItem a = line(po, MAT_A, "100", "10", "13");
        receipt(po, a, "100");

        // 总额与订单含税额完全相等，但发票上的物料是 B
        JsonNode inv = upload(po, "1130.00", List.of(lineReq(a, null, MAT_B, "100", "1130.00")), 200);

        assertThat(inv.at("/data/reconcileStatus").asText()).isEqualTo("PENDING");
        assertThat(inv.at("/data/reconcileNote").asText()).contains("MAT-B").contains("MAT-A");
        JsonNode views = body(mvc.perform(get("/api/mobile/" + F + "/invoices/views").param("poId", po.getId()))
                .andReturn(), 200);
        assertThat(views.at("/data/0/lines/0/matchStatus").asText()).isEqualTo("MATERIAL_MISMATCH");

        String invoiceId = inv.at("/data/id").asText();
        JsonNode refused = body(mvc.perform(put("/api/mobile/" + F + "/invoices/" + invoiceId + "/reconcile")
                .header("Authorization", "Bearer t").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reconcileStatus\":\"MATCHED\"}")).andReturn(), 409);
        assertThat(refused.at("/message").asText()).contains("行级差异");
        em.flush(); em.clear();
        assertThat(em.find(PurchaseInvoice.class, invoiceId).getReconcileStatus()).isEqualTo("PENDING");
    }

    @Test
    void s5_06_shortReceiptInvoicedAtOrderedQuantity_isRejectedWithHint() throws Exception {
        PurchaseOrder po = order();
        PurchaseOrderItem a = line(po, MAT_A, "100", "10", "13");
        receipt(po, a, "60");   // 少收 40

        JsonNode refused = upload(po, "1130.00", List.of(lineReq(a, null, MAT_A, "100", "1130.00")), 409);

        assertThat(refused.at("/message").asText()).contains("超开").contains("最多还可开 60");
        assertThat(refused.toString()).contains("有效收货 60");
        assertThat(countInvoices(po)).isZero();   // 409 不留孤立表头发票
    }

    // ─── S5-07 ────────────────────────────────────────────────────────────────

    @Test
    void s5_07_splitInvoicesAgainstPartialReceiptsAndReturn_conserveQuantities() throws Exception {
        PurchaseOrder po = order();
        PurchaseOrderItem a = line(po, MAT_A, "100", "10", "0");
        PurchaseReceiveItem r1 = receipt(po, a, "60");
        PurchaseReceiveItem r2 = receipt(po, a, "40");
        purchaseReturn(po, MAT_A, "10", ReturnOrderStatus.APPROVED);
        purchaseReturn(po, MAT_A, "99", ReturnOrderStatus.REJECTED);   // 驳回的不抵减

        upload(po, "500.00", List.of(lineReq(a, r1, MAT_A, "50", "500.00")), 200);

        // 采购单行还剩 40，但收货行 R1 只剩 10，开 15 → 收货行超开
        JsonNode overReceipt = upload(po, "150.00", List.of(lineReq(a, r1, MAT_A, "15", "150.00")), 409);
        assertThat(overReceipt.at("/message").asText()).contains("收货行").contains("剩余 10");
        // 指向 R2 的 40 合法；单价开错（应开 400 开了 410）→ 登记成功但待核对，仍占量
        JsonNode second = upload(po, "410.00", List.of(lineReq(a, r2, MAT_A, "40", "410.00")), 200);
        assertThat(second.at("/data/reconcileStatus").asText()).isEqualTo("PENDING");

        // 有效收货 100 − 退货 10 − 已开 90 = 0
        JsonNode over = upload(po, "10.00", List.of(lineReq(a, null, MAT_A, "1", "10.00")), 409);
        assertThat(over.at("/message").asText()).contains("最多还可开 0");

        JsonNode status = matchStatus(po);
        assertThat(status.at("/data/lines/0/receivedQuantity").decimalValue()).isEqualByComparingTo("100");
        assertThat(status.at("/data/lines/0/returnedQuantity").decimalValue()).isEqualByComparingTo("10");
        assertThat(status.at("/data/lines/0/invoiceableQuantity").decimalValue()).isEqualByComparingTo("90");
        assertThat(status.at("/data/lines/0/invoicedQuantity").decimalValue()).isEqualByComparingTo("90");
        assertThat(status.at("/data/lines/0/remainingQuantity").decimalValue()).isEqualByComparingTo("0");

        // 第二张票核为「不符」→ 释放 40，可以重开
        body(mvc.perform(put("/api/mobile/" + F + "/invoices/" + second.at("/data/id").asText() + "/reconcile")
                .header("Authorization", "Bearer t").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reconcileStatus\":\"MISMATCHED\",\"note\":\"供应商开票错误\"}")).andReturn(), 200);
        assertThat(matchStatus(po).at("/data/lines/0/remainingQuantity").decimalValue()).isEqualByComparingTo("40");
        JsonNode reissued = upload(po, "400.00", List.of(lineReq(a, r2, MAT_A, "40", "400.00")), 200);
        assertThat(reissued.at("/data/reconcileStatus").asText()).isEqualTo("MATCHED");
        JsonNode done = matchStatus(po);
        assertThat(done.at("/data/lines/0/remainingQuantity").decimalValue()).isEqualByComparingTo("0");
        // 第一张 50 也是自动相符 → 90 全部核对 → 采购单收票闭环
        assertThat(done.at("/data/lines/0/matchedQuantity").decimalValue()).isEqualByComparingTo("90");
        assertThat(done.at("/data/fullyMatched").asBoolean()).isTrue();
    }

    @Test
    void s5_07_sameReceiptLineCannotBeInvoicedTwice() throws Exception {
        PurchaseOrder po = order();
        PurchaseOrderItem a = line(po, MAT_A, "100", "10", "0");
        PurchaseReceiveItem r1 = receipt(po, a, "60");
        receipt(po, a, "40");

        upload(po, "600.00", List.of(lineReq(a, r1, MAT_A, "60", "600.00")), 200);
        // 采购单行还剩 40，但 R1 已被开满
        JsonNode refused = upload(po, "10.00", List.of(lineReq(a, r1, MAT_A, "1", "10.00")), 409);
        assertThat(refused.at("/message").asText()).contains("已被开票 60");
    }

    @Test
    void lineFromAnotherOrder_isRejected() throws Exception {
        PurchaseOrder po = order();
        line(po, MAT_A, "100", "10", "0");
        PurchaseOrder other = order();
        PurchaseOrderItem foreign = line(other, MAT_A, "100", "10", "0");
        receipt(other, foreign, "100");

        JsonNode refused = upload(po, "100.00", List.of(lineReq(foreign, null, MAT_A, "10", "100.00")), 400);
        assertThat(refused.at("/message").asText()).contains("不属于采购单");
        assertThat(countInvoices(po)).isZero();
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private JsonNode upload(PurchaseOrder po, String amount, List<Map<String, Object>> lines, int expectStatus)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("purchaseOrderId", po.getId());
        body.put("supplierId", po.getSupplierId());
        body.put("invoiceNumber", "FP-" + (++seq) + "-" + po.getOrderNumber());
        body.put("amount", new BigDecimal(amount));
        body.put("lines", lines);
        MvcResult result = mvc.perform(post("/api/mobile/" + F + "/invoices/upload")
                .header("Authorization", "Bearer t")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andReturn();
        return body(result, expectStatus);
    }

    private JsonNode reconcile(String invoiceId, String status, int expectStatus) throws Exception {
        return body(mvc.perform(put("/api/mobile/" + F + "/invoices/" + invoiceId + "/reconcile")
                .header("Authorization", "Bearer t").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reconcileStatus\":\"" + status + "\"}")).andReturn(), expectStatus);
    }

    private JsonNode matchStatus(PurchaseOrder po) throws Exception {
        em.flush();
        return body(mvc.perform(get("/api/mobile/" + F + "/invoices/match-status").param("poId", po.getId()))
                .andReturn(), 200);
    }

    private JsonNode body(MvcResult result, int expectStatus) throws Exception {
        String text = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus()).as(text).isEqualTo(expectStatus);
        return json.readTree(text);
    }

    private Map<String, Object> lineReq(PurchaseOrderItem item, PurchaseReceiveItem receipt, String material,
                                        String qty, String amount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("purchaseOrderItemId", item.getId());
        if (receipt != null) m.put("receiveItemId", receipt.getId());
        m.put("materialTypeId", material);
        m.put("quantity", new BigDecimal(qty));
        m.put("amount", new BigDecimal(amount));
        return m;
    }

    private long countInvoices(PurchaseOrder po) {
        em.flush();
        return em.createQuery("SELECT COUNT(i) FROM PurchaseInvoice i WHERE i.purchaseOrderId = :po", Long.class)
                .setParameter("po", po.getId()).getSingleResult();
    }

    private PurchaseOrder order() {
        PurchaseOrder po = new PurchaseOrder();
        po.setFactoryId(F);
        po.setOrderNumber("CG-TW-" + (++seq));
        po.setSupplierId("SUP-TW");
        po.setOrderDate(LocalDate.of(2026, 10, 1));
        po.setStatus(PurchaseOrderStatus.COMPLETED);
        po.setCreatedBy(7L);
        po.setTotalAmount(new BigDecimal("0"));
        po.setTaxAmount(new BigDecimal("0"));
        em.persist(po);
        return po;
    }

    private PurchaseOrderItem line(PurchaseOrder po, String material, String qty, String price, String taxRate) {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setPurchaseOrderId(po.getId());
        item.setMaterialTypeId(material);
        item.setMaterialName(material);
        item.setQuantity(new BigDecimal(qty));
        item.setUnit("kg");
        item.setPriceUnit("kg");
        item.setInventoryBaseUnitSnapshot("kg");
        item.setUnitPrice(new BigDecimal(price));
        item.setTaxRate(new BigDecimal(taxRate));
        em.persist(item);
        // 订单表头 = 各行含税合计，用于「表头相等」的对照
        BigDecimal untaxed = item.getLineAmount();
        po.setTotalAmount(po.getTotalAmount().add(untaxed));
        po.setTaxAmount(po.getTaxAmount().add(item.getLineAmountWithTax().subtract(untaxed)));
        return item;
    }

    private PurchaseReceiveItem receipt(PurchaseOrder po, PurchaseOrderItem item, String qty) {
        PurchaseReceiveRecord record = new PurchaseReceiveRecord();
        record.setFactoryId(F);
        record.setReceiveNumber("SH-" + (++seq));
        record.setPurchaseOrderId(po.getId());
        record.setSupplierId(po.getSupplierId());
        record.setReceiveDate(LocalDate.of(2026, 10, 2));
        record.setStatus(PurchaseReceiveStatus.CONFIRMED);
        record.setReceivedBy(7L);
        em.persist(record);
        PurchaseReceiveItem ri = new PurchaseReceiveItem();
        ri.setReceiveRecordId(record.getId());
        ri.setPurchaseOrderItemId(item.getId());
        ri.setMaterialTypeId(item.getMaterialTypeId());
        ri.setReceivedQuantity(new BigDecimal(qty));
        ri.setUnit(item.getUnit());
        ri.setPriceUnit("kg");
        em.persist(ri);
        // 同一张单上一条未确认的草稿收货 —— 不算有效收货
        PurchaseReceiveRecord draft = new PurchaseReceiveRecord();
        draft.setFactoryId(F);
        draft.setReceiveNumber("SH-D-" + (++seq));
        draft.setPurchaseOrderId(po.getId());
        draft.setSupplierId(po.getSupplierId());
        draft.setReceiveDate(LocalDate.of(2026, 10, 2));
        draft.setStatus(PurchaseReceiveStatus.DRAFT);
        draft.setReceivedBy(7L);
        em.persist(draft);
        PurchaseReceiveItem draftItem = new PurchaseReceiveItem();
        draftItem.setReceiveRecordId(draft.getId());
        draftItem.setPurchaseOrderItemId(item.getId());
        draftItem.setMaterialTypeId(item.getMaterialTypeId());
        draftItem.setReceivedQuantity(new BigDecimal("1000"));
        draftItem.setUnit(item.getUnit());
        draftItem.setPriceUnit("kg");
        em.persist(draftItem);
        return ri;
    }

    private void purchaseReturn(PurchaseOrder po, String material, String qty, ReturnOrderStatus status) {
        ReturnOrder ro = new ReturnOrder();
        ro.setFactoryId(F);
        ro.setReturnNumber("TH-" + (++seq));
        ro.setReturnType(ReturnType.PURCHASE_RETURN);
        ro.setStatus(status);
        ro.setCounterpartyId(po.getSupplierId());
        ro.setSourceOrderId(po.getId());
        ro.setReturnDate(LocalDate.of(2026, 10, 3));
        ro.setWithGoods(true);
        ro.setCreatedBy(7L);
        ro.setItems(new ArrayList<>());
        em.persist(ro);
        ReturnOrderItem item = new ReturnOrderItem();
        item.setReturnOrderId(ro.getId());
        item.setMaterialTypeId(material);
        item.setQuantity(new BigDecimal(qty));
        em.persist(item);
    }
}
