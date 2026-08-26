package com.cretas.aims.service.impl;

import com.cretas.aims.dto.label.MaterialBatchLabelScanResponse;
import com.cretas.aims.entity.Label;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.repository.LabelRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SP4-T5: 物料批次标签生成 + 扫码查询.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SP4-T5 LabelService: generateMaterialBatchLabel + scanLabel")
class LabelServiceSp4T5Test {

    @Mock
    LabelRepository labelRepository;

    @Mock
    MaterialBatchRepository materialBatchRepository;

    @InjectMocks
    LabelServiceImpl labelService;

    private MaterialBatch batch;
    private static final String FACTORY_ID = "F006";
    private static final String BATCH_ID = "MB-2026-001";

    @BeforeEach
    void setUp() {
        batch = new MaterialBatch();
        batch.setId(BATCH_ID);
        batch.setFactoryId(FACTORY_ID);
        batch.setBatchNumber("YL-001");
        batch.setFactoryNumber("FN-LIUSHANMEN-01");
        batch.setOriginPlace("昆山市张家港路");
        batch.setCreatedAt(LocalDateTime.of(2026, 10, 2, 9, 0));
    }

    // ──────────────── generateMaterialBatchLabel ────────────────

    @Test
    @DisplayName("T5-G1: 正常生成 — 批次存在, 无已有标签 → 创建并返回 ACTIVE 标签")
    void generateLabel_happyPath() {
        when(materialBatchRepository.findByIdAndFactoryId(BATCH_ID, FACTORY_ID))
                .thenReturn(Optional.of(batch));
        when(labelRepository.findByBatchIdAndDeletedAtIsNull(BATCH_ID))
                .thenReturn(java.util.List.of());
        when(labelRepository.save(any(Label.class))).thenAnswer(inv -> inv.getArgument(0));

        Label result = labelService.generateMaterialBatchLabel(FACTORY_ID, BATCH_ID, 42L);

        assertNotNull(result);
        assertEquals("MATERIAL", result.getBatchType());
        assertEquals(BATCH_ID, result.getBatchId());
        assertEquals(FACTORY_ID, result.getFactoryId());
        assertEquals("ACTIVE", result.getStatus());
        assertEquals(0, result.getPrintCount());
        assertEquals(42L, result.getCreatedBy());
    }

    @Test
    @DisplayName("T5-G2: 批次不存在 → 抛 RuntimeException")
    void generateLabel_batchNotFound() {
        when(materialBatchRepository.findByIdAndFactoryId(BATCH_ID, FACTORY_ID))
                .thenReturn(Optional.empty());

        assertThrows(RuntimeException.class,
                () -> labelService.generateMaterialBatchLabel(FACTORY_ID, BATCH_ID, 1L));
    }

    @Test
    @DisplayName("T5-G3: 批次已有 ACTIVE 标签 → 409 (重复生成防呆)")
    void generateLabel_duplicateActiveLabel_throws409() {
        Label existingLabel = new Label();
        existingLabel.setId("existing-label-id");
        existingLabel.setBatchId(BATCH_ID);
        existingLabel.setStatus("ACTIVE");

        when(materialBatchRepository.findByIdAndFactoryId(BATCH_ID, FACTORY_ID))
                .thenReturn(Optional.of(batch));
        when(labelRepository.findByBatchIdAndDeletedAtIsNull(BATCH_ID))
                .thenReturn(java.util.List.of(existingLabel));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> labelService.generateMaterialBatchLabel(FACTORY_ID, BATCH_ID, 1L));
        assertTrue(ex.getMessage().contains("已存在") || ex.getMessage().contains("already"),
                "Exception message should indicate duplicate label: " + ex.getMessage());
    }

    // ──────────────── scanLabel ────────────────

    @Test
    @DisplayName("T5-S1: 扫码成功 — ACTIVE 标签 → 返回完整批次信息")
    void scanLabel_activeLabel_returnsFullResponse() {
        String labelCode = "QR-F006-20261002120000-0001";
        Label label = new Label();
        label.setId("label-id-001");
        label.setFactoryId(FACTORY_ID);
        label.setLabelCode(labelCode);
        label.setBatchType("MATERIAL");
        label.setBatchId(BATCH_ID);
        label.setStatus("ACTIVE");
        label.setTraceCode("TRACE-F006-20261002-000001");
        label.setCreatedAt(LocalDateTime.of(2026, 10, 2, 10, 0));

        when(labelRepository.findByLabelCodeAndDeletedAtIsNull(labelCode))
                .thenReturn(Optional.of(label));
        when(materialBatchRepository.findByIdAndFactoryId(BATCH_ID, FACTORY_ID))
                .thenReturn(Optional.of(batch));

        MaterialBatchLabelScanResponse resp = labelService.scanLabel(FACTORY_ID, labelCode);

        assertNotNull(resp);
        assertEquals("label-id-001", resp.getLabelId());
        assertEquals(labelCode, resp.getLabelCode());
        assertEquals("ACTIVE", resp.getLabelStatus());
        assertEquals(BATCH_ID, resp.getBatchId());
        assertEquals("YL-001", resp.getBatchNumber());
        assertEquals("FN-LIUSHANMEN-01", resp.getFactoryNumber());
        assertEquals("昆山市张家港路", resp.getOriginPlace());
        assertEquals("TRACE-F006-20261002-000001", resp.getTraceCode());
    }

    @Test
    @DisplayName("T5-S2: 标签不存在 → 抛 RuntimeException")
    void scanLabel_notFound() {
        when(labelRepository.findByLabelCodeAndDeletedAtIsNull("UNKNOWN-CODE"))
                .thenReturn(Optional.empty());

        assertThrows(RuntimeException.class,
                () -> labelService.scanLabel(FACTORY_ID, "UNKNOWN-CODE"));
    }

    @Test
    @DisplayName("T5-S3: VOIDED 标签 → 抛 RuntimeException (禁止使用废标签)")
    void scanLabel_voidedLabel_throws() {
        String labelCode = "VOIDED-CODE";
        Label voidedLabel = new Label();
        voidedLabel.setId("voided-id");
        voidedLabel.setFactoryId(FACTORY_ID);
        voidedLabel.setLabelCode(labelCode);
        voidedLabel.setBatchId(BATCH_ID);
        voidedLabel.setStatus("VOIDED");

        when(labelRepository.findByLabelCodeAndDeletedAtIsNull(labelCode))
                .thenReturn(Optional.of(voidedLabel));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> labelService.scanLabel(FACTORY_ID, labelCode));
        assertTrue(ex.getMessage().contains("VOIDED") || ex.getMessage().contains("作废"),
                "Exception message should indicate voided status: " + ex.getMessage());
    }

    // ──────────────── SP4-A3: SP8 primaryCode 前缀 ────────────────

    @Test
    @DisplayName("T5-A3-1: SP8 primaryCode='001' → 标签前缀取前2字符 '00' (generateLabelCode substring(0,2))")
    void generateLabel_withSp8PrimaryCode_usesPrefixFromMaterialType() {
        RawMaterialType materialType = new RawMaterialType();
        materialType.setPrimaryCode("001");
        batch.setMaterialType(materialType);

        when(materialBatchRepository.findByIdAndFactoryId(BATCH_ID, FACTORY_ID))
                .thenReturn(Optional.of(batch));
        when(labelRepository.findByBatchIdAndDeletedAtIsNull(BATCH_ID))
                .thenReturn(java.util.List.of());
        when(labelRepository.save(any(Label.class))).thenAnswer(inv -> inv.getArgument(0));

        Label result = labelService.generateMaterialBatchLabel(FACTORY_ID, BATCH_ID, 1L);

        assertNotNull(result);
        assertNotNull(result.getLabelCode(), "labelCode should be generated");
        // generateLabelCode internally does labelType.substring(0, 2)
        // SP8 primaryCode "001" → substring(0,2) → "00" as prefix in label code
        assertTrue(result.getLabelCode().startsWith("00"),
                "label code should start with '00' (primaryCode '001' substring(0,2)), got: " + result.getLabelCode());
    }

    @Test
    @DisplayName("T5-A3-2: primaryCode=null + 类别'原料' → fallback 类别前缀 'YL' (非旧硬编码 MA)")
    void generateLabel_nullPrimaryCode_fallsBackToCategoryPrefix() {
        RawMaterialType materialType = new RawMaterialType();
        materialType.setPrimaryCode(null); // null → fallback 走类别前缀
        materialType.setCategory("原料");   // 原料 → YL
        batch.setMaterialType(materialType);

        when(materialBatchRepository.findByIdAndFactoryId(BATCH_ID, FACTORY_ID))
                .thenReturn(Optional.of(batch));
        when(labelRepository.findByBatchIdAndDeletedAtIsNull(BATCH_ID))
                .thenReturn(java.util.List.of());
        when(labelRepository.save(any(Label.class))).thenAnswer(inv -> inv.getArgument(0));

        Label result = labelService.generateMaterialBatchLabel(FACTORY_ID, BATCH_ID, 1L);

        assertNotNull(result.getLabelCode(), "labelCode should be generated");
        // 修(Gate2 靶6): primaryCode 空时用 SP4 类别前缀, 原料→YL, 不再硬编码 MA
        assertTrue(result.getLabelCode().startsWith("YL"),
                "label code should start with 'YL' (类别前缀 fallback), got: " + result.getLabelCode());
    }

    @Test
    @DisplayName("T5-A3-3: materialType=null → fallback 前缀 'WL' (其他/未知类别, 非旧 MA)")
    void generateLabel_noMaterialType_fallsBackToWL() {
        // batch has no materialType set (null) → 类别未知 → WL
        batch.setMaterialType(null);

        when(materialBatchRepository.findByIdAndFactoryId(BATCH_ID, FACTORY_ID))
                .thenReturn(Optional.of(batch));
        when(labelRepository.findByBatchIdAndDeletedAtIsNull(BATCH_ID))
                .thenReturn(java.util.List.of());
        when(labelRepository.save(any(Label.class))).thenAnswer(inv -> inv.getArgument(0));

        Label result = labelService.generateMaterialBatchLabel(FACTORY_ID, BATCH_ID, 1L);

        assertNotNull(result.getLabelCode());
        assertTrue(result.getLabelCode().startsWith("WL"),
                "label code should start with 'WL' (fallback when materialType null), got: " + result.getLabelCode());
    }

    @Test
    @DisplayName("T5-S4: 标签跨工厂访问 → 抛 RuntimeException")
    void scanLabel_crossFactoryAccess_throws() {
        String labelCode = "QR-OTHER-FACTORY";
        Label label = new Label();
        label.setId("label-id-other");
        label.setFactoryId("F999"); // different factory
        label.setLabelCode(labelCode);
        label.setStatus("ACTIVE");
        label.setBatchId(BATCH_ID);

        when(labelRepository.findByLabelCodeAndDeletedAtIsNull(labelCode))
                .thenReturn(Optional.of(label));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> labelService.scanLabel(FACTORY_ID, labelCode));
        assertTrue(ex.getMessage().contains("工厂") || ex.getMessage().contains("factory") ||
                        ex.getMessage().contains("权限") || ex.getMessage().contains("access"),
                "Exception should indicate factory mismatch: " + ex.getMessage());
    }

    // ──────────────── 往返 (print → scan) ────────────────
    //
    // 既有的 4 个 scanLabel 用例喂的都是 labelCode (或字面量 "UNKNOWN-CODE") —— 它们按
    // **被测代码想要的形状**构造输入。而打印机印进二维码的不是 labelCode:
    //   WHBatchDetailScreen.tsx:217   qrContent = genRes.data?.traceCode ?? genRes.data?.labelCode
    //   BatchLabelPrintService.ts:65  const qrText = qrContent ?? batchNumber
    // generateMaterialBatchLabel 恒设 traceCode (LabelServiceImpl:252), 所以 traceCode 恒胜出,
    // 贴在实物上的二维码里是 "TRACE-...". 下面这条测试按**打印机实际产出的形状**喂输入.

    /** 复刻 ②③ 两处前端逻辑: 二维码里真正印的那个串. */
    private static String codeActuallyPrinted(Label label) {
        return label.getTraceCode() != null ? label.getTraceCode() : label.getLabelCode();
    }

    /** 让 batch 带上可生成前缀的 materialType (category=null 时 getMaterialCategoryPrefix 按设计抛异常). */
    private void givenBatchWithMaterialType() {
        RawMaterialType materialType = new RawMaterialType();
        materialType.setName("鲈鱼");
        materialType.setCategory("原料");
        batch.setMaterialType(materialType);
    }

    @Test
    @DisplayName("T5-RT1: 往返 — 打印机印进二维码的那个码 (traceCode ?? labelCode) 必须能扫回来")
    void printedCode_roundTrips_throughScanLabel() {
        givenBatchWithMaterialType();
        when(materialBatchRepository.findByIdAndFactoryId(BATCH_ID, FACTORY_ID))
                .thenReturn(Optional.of(batch));
        when(labelRepository.findByBatchIdAndDeletedAtIsNull(BATCH_ID))
                .thenReturn(java.util.List.of());
        when(labelRepository.save(any(Label.class))).thenAnswer(inv -> inv.getArgument(0));

        Label generated = labelService.generateMaterialBatchLabel(FACTORY_ID, BATCH_ID, 1L);
        String printed = codeActuallyPrinted(generated);

        // 前提断言: 确认这条测试真的在测 traceCode 那条路 (否则它就是恒真式)
        assertTrue(printed.startsWith("TRACE-"),
                "前提不成立 — 打印机印的应该是 traceCode, 实际印的是: " + printed);
        assertNotEquals(generated.getLabelCode(), printed,
                "前提不成立 — 印出来的码与 labelCode 相同, 那就没有往返问题可测了");

        // 按真库会给出的答案打桩: label_code 列里没有 "TRACE-..." 这个值, trace_code 列里有.
        when(labelRepository.findByLabelCodeAndDeletedAtIsNull(printed))
                .thenReturn(Optional.empty());
        lenient().when(labelRepository.findByTraceCodeAndDeletedAtIsNull(printed))
                .thenReturn(Optional.of(generated));

        MaterialBatchLabelScanResponse resp = labelService.scanLabel(FACTORY_ID, printed);

        assertNotNull(resp, "扫打印出来的码应该能查到标签");
        assertEquals(generated.getLabelCode(), resp.getLabelCode());
        assertEquals(printed, resp.getTraceCode());
        assertEquals(BATCH_ID, resp.getBatchId());
    }

    @Test
    @DisplayName("T5-RT2: 阴性对照 — 既不是 labelCode 也不是 traceCode 的串, 仍然抛「标签不存在」")
    void unknownCode_matchesNeitherColumn_stillThrows() {
        String garbage = "NOT-A-REAL-CODE-XYZ";
        when(labelRepository.findByLabelCodeAndDeletedAtIsNull(garbage))
                .thenReturn(Optional.empty());
        when(labelRepository.findByTraceCodeAndDeletedAtIsNull(garbage))
                .thenReturn(Optional.empty());

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> labelService.scanLabel(FACTORY_ID, garbage));
        assertTrue(ex.getMessage().contains("标签不存在"),
                "兜底不该变成「什么都能查到」, 应仍报标签不存在: " + ex.getMessage());
    }

    @Test
    @DisplayName("T5-RT3: 跨工厂防护对兜底分支同样生效 — 用 A 厂身份扫 B 厂标签的 traceCode 必须被拒")
    void crossFactory_viaTraceCodeFallback_stillDenied() {
        String otherFactoryTraceCode = "TRACE-F999-20260825-000001";
        Label otherFactoryLabel = new Label();
        otherFactoryLabel.setId("label-id-f999");
        otherFactoryLabel.setFactoryId("F999");                  // 属于别的工厂
        otherFactoryLabel.setLabelCode("YL-F999-20260825120000-0001");
        otherFactoryLabel.setTraceCode(otherFactoryTraceCode);
        otherFactoryLabel.setStatus("ACTIVE");
        otherFactoryLabel.setBatchId(BATCH_ID);

        // 只有 traceCode 命中 → 强制走兜底分支
        when(labelRepository.findByLabelCodeAndDeletedAtIsNull(otherFactoryTraceCode))
                .thenReturn(Optional.empty());
        when(labelRepository.findByTraceCodeAndDeletedAtIsNull(otherFactoryTraceCode))
                .thenReturn(Optional.of(otherFactoryLabel));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> labelService.scanLabel(FACTORY_ID, otherFactoryTraceCode));
        assertTrue(ex.getMessage().contains("工厂") || ex.getMessage().contains("factory"),
                "走兜底分支不该绕过跨工厂防护: " + ex.getMessage());
        // 阴性对照: 别把跨工厂拒绝误报成「标签不存在」—— 那会掩盖真正的越权
        assertFalse(ex.getMessage().contains("标签不存在"),
                "应报跨工厂越权而非标签不存在: " + ex.getMessage());
    }

    @Test
    @DisplayName("T5-RT4: VOIDED 检查对兜底分支同样生效 — 扫已作废标签的 traceCode 必须被拒")
    void voidedLabel_viaTraceCodeFallback_stillDenied() {
        String voidedTraceCode = "TRACE-F006-20260825-000002";
        Label voided = new Label();
        voided.setId("label-id-voided");
        voided.setFactoryId(FACTORY_ID);
        voided.setLabelCode("YL-F006-20260825120000-0002");
        voided.setTraceCode(voidedTraceCode);
        voided.setStatus("VOIDED");
        voided.setBatchId(BATCH_ID);

        when(labelRepository.findByLabelCodeAndDeletedAtIsNull(voidedTraceCode))
                .thenReturn(Optional.empty());
        when(labelRepository.findByTraceCodeAndDeletedAtIsNull(voidedTraceCode))
                .thenReturn(Optional.of(voided));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> labelService.scanLabel(FACTORY_ID, voidedTraceCode));
        assertTrue(ex.getMessage().contains("VOIDED") || ex.getMessage().contains("作废"),
                "走兜底分支不该绕过 VOIDED 检查: " + ex.getMessage());
    }
}
