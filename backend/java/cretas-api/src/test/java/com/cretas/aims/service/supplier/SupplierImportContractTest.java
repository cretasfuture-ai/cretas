package com.cretas.aims.service.supplier;

import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.SupplierImportReceipt;
import com.cretas.aims.dto.supplier.SupplierDTO;
import com.cretas.aims.dto.supplier.SupplierImportConfirmRequest;
import com.cretas.aims.dto.supplier.SupplierImportPreviewDTO;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.SupplierImportReceiptRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.service.SupplierService;
import com.cretas.aims.utils.ExcelUtil;
import org.springframework.dao.DataIntegrityViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SupplierImportContractTest {
    @Mock ExcelUtil excelUtil;
    @Mock SupplierRepository supplierRepository;
    @Mock SupplierImportReceiptRepository receiptRepository;
    @Mock SupplierService supplierService;
    SupplierImportServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SupplierImportServiceImpl(excelUtil, supplierRepository, receiptRepository, supplierService);
        // lenient: 幂等重放 / 冲突 / 校验失败三条出口都在读供应商名单**之前**就返回或抛错,
        // 这个默认桩对它们用不上 —— 严格模式会因此报 UnnecessaryStubbing 而不是因为测试有问题。
        lenient().when(supplierRepository.findByFactoryId("F006")).thenReturn(List.of());
    }

    @Test
    void smartModeRecognizesAliasesAndSeparatesValidErrorDuplicateAndBlankRows() {
        List<String> headers = List.of("供货商", "负责人", "手机", "联系地址", "纳税人识别号");
        Map<String, String> valid = row(headers, "甲供应商", "张三", "13812345678", "上海市浦东新区1号", "TAX-1");
        Map<String, String> duplicate = row(headers, "甲供应商", "李四", "021-61234567", "上海市浦东新区2号", "TAX-2");
        Map<String, String> invalid = row(headers, "乙供应商", "王五", "123", "---", "TAX-3");
        Map<String, String> blank = row(headers, "", "", "", "", "");
        when(excelUtil.readFirstSheetAsRows(any(InputStream.class), eq(1000)))
                .thenReturn(new ExcelUtil.RawSheet(headers, List.of(valid, duplicate, invalid, blank)));

        var preview = service.preview("F006", new byte[]{1, 2, 3}, "SMART", null);

        assertThat(preview.getCounts()).containsEntry("valid", 1).containsEntry("duplicate", 1)
                .containsEntry("error", 1).containsEntry("ignored", 1);
        assertThat(preview.getMappings()).extracting("targetField")
                .contains("name", "contactPerson", "phone", "address", "taxNumber");
        assertThat(preview.getRows().get(2).getErrors()).containsKeys("phone", "address");
    }

    @Test
    void databaseDuplicateIsScopedToCurrentFactory() {
        Supplier existing = new Supplier();
        existing.setName("甲供应商"); existing.setTaxNumber("TAX-1");
        when(supplierRepository.findByFactoryId("F006")).thenReturn(List.of(existing));
        List<String> headers = List.of("供应商名称", "联系人", "联系电话", "地址", "税号");
        when(excelUtil.readFirstSheetAsRows(any(InputStream.class), eq(1000)))
                .thenReturn(new ExcelUtil.RawSheet(headers,
                        List.of(row(headers, "甲供应商", "张三", "13812345678", "上海市1号", "TAX-1"))));

        var preview = service.preview("F006", new byte[]{9}, "STANDARD", null);

        assertThat(preview.getRows()).singleElement().extracting("classification").isEqualTo("DUPLICATE");
    }

    @Test
    void confirmClaimsIdempotencyReceiptBeforeCreatingSuppliers() {
        SupplierImportConfirmRequest request = new SupplierImportConfirmRequest();
        request.setFileDigest("a".repeat(64)); request.setIdempotencyKey("import-1");
        SupplierImportPreviewDTO.SupplierRowData row = new SupplierImportPreviewDTO.SupplierRowData();
        row.setName("甲供应商"); row.setContactPerson("张三"); row.setPhone("13812345678");
        row.setAddress("上海市1号"); request.setRows(List.of(row));
        when(receiptRepository.findByFactoryIdAndIdempotencyKey("F006", "import-1")).thenReturn(Optional.empty());
        when(receiptRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            SupplierImportReceipt receipt = invocation.getArgument(0); receipt.setId("receipt-1"); return receipt;
        });
        when(supplierService.createSupplier(eq("F006"), any(), eq(1309L)))
                .thenReturn(SupplierDTO.builder().id("supplier-1").name("甲供应商").build());
        when(receiptRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.confirm("F006", request, 1309L);

        assertThat(result.getCreatedCount()).isEqualTo(1);
        assertThat(result.getReplayed()).isFalse();
        var order = inOrder(receiptRepository, supplierService);
        order.verify(receiptRepository).saveAndFlush(any());
        order.verify(supplierService).createSupplier(eq("F006"), any(), eq(1309L));
    }

    // ===================================================================
    // 幂等的三条出口 (2026-08-25 补)
    //
    // 上面那条只证明了「首次确认会先占幂等键再建供应商」, 但幂等真正要防的事情
    // —— **重放不许再建一遍** —— 此前没有任何断言。仓管重复点「确认导入」或前端
    // 超时重试, 是这条路径最现实的输入。
    // ===================================================================

    /**
     * 同键 + 同文件: 返回既有结果, {@code replayed=true}, 且**一个供应商都不再创建**。
     * 这是幂等对用户的全部承诺; 断言 {@code verifyNoInteractions(supplierService)} 而不是
     * 只看 createdCount —— 后者在「又建了一遍又恰好数目相同」时同样成立。
     */
    @Test
    void replayWithSameDigestReturnsExistingResultAndCreatesNothing() {
        SupplierImportConfirmRequest request = new SupplierImportConfirmRequest();
        request.setFileDigest("A".repeat(64));          // 大小写不同, 服务按 equalsIgnoreCase 比
        request.setIdempotencyKey("import-1");
        request.setRows(List.of());
        SupplierImportReceipt receipt = SupplierImportReceipt.builder()
                .id("receipt-1").factoryId("F006").idempotencyKey("import-1")
                .fileDigest("a".repeat(64)).createdCount(2).supplierIds("s-1,s-2").build();
        when(receiptRepository.findByFactoryIdAndIdempotencyKey("F006", "import-1"))
                .thenReturn(Optional.of(receipt));
        when(supplierRepository.findByIdInAndFactoryId(List.of("s-1", "s-2"), "F006"))
                .thenReturn(List.of());

        var result = service.confirm("F006", request, 1309L);

        assertThat(result.getReplayed()).isTrue();
        assertThat(result.getCreatedCount()).isEqualTo(2);   // 回放的是**当初**建了几个
        verifyNoInteractions(supplierService);               // ⛔ 不许再建
        verify(receiptRepository, never()).saveAndFlush(any());
    }

    /**
     * 同键 + 不同文件: 409。⛔ 不能静默按旧结果回放 —— 那会让「换了个文件重传」
     * 看起来成功了, 而新文件里的供应商一个都没进去。
     */
    @Test
    void sameKeyWithDifferentFileIsRejected() {
        SupplierImportConfirmRequest request = new SupplierImportConfirmRequest();
        request.setFileDigest("b".repeat(64));
        request.setIdempotencyKey("import-1");
        request.setRows(List.of());
        when(receiptRepository.findByFactoryIdAndIdempotencyKey("F006", "import-1"))
                .thenReturn(Optional.of(SupplierImportReceipt.builder()
                        .id("receipt-1").factoryId("F006").idempotencyKey("import-1")
                        .fileDigest("a".repeat(64)).createdCount(1).supplierIds("s-1").build()));

        assertThatThrownBy(() -> service.confirm("F006", request, 1309L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("幂等键已用于其他导入文件");

        verifyNoInteractions(supplierService);
    }

    /**
     * 并发抢同一个幂等键: 占键时撞唯一约束 -> 409, 且**在建任何供应商之前**。
     * 这条守的是「本地 synchronized 只能串行化同进程, 跨 pod 靠 DB 唯一约束」那段注释
     * 描述的行为 —— 注释不是断言, 所以这里把它钉住。
     */
    @Test
    void concurrentClaimLosesOnUniqueConstraintBeforeCreatingAnySupplier() {
        SupplierImportConfirmRequest request = new SupplierImportConfirmRequest();
        request.setFileDigest("c".repeat(64));
        request.setIdempotencyKey("import-2");
        SupplierImportPreviewDTO.SupplierRowData row = new SupplierImportPreviewDTO.SupplierRowData();
        row.setName("甲供应商"); row.setContactPerson("张三");
        row.setPhone("13812345678"); row.setAddress("上海市1号");
        request.setRows(List.of(row));
        when(receiptRepository.findByFactoryIdAndIdempotencyKey("F006", "import-2"))
                .thenReturn(Optional.empty());
        when(receiptRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service.confirm("F006", request, 1309L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("同一导入请求正在处理或已经完成");

        verifyNoInteractions(supplierService);
    }

    /**
     * 阴性对照: 行校验失败必须在**建任何供应商之前**抛 400。
     * 若哪天校验被挪到创建循环之后, 就会出现「前半截建成功、后半截报错」的半成品导入
     * —— 而 confirm 是 @Transactional, 表面上会回滚, 但 createSupplier 里的
     * 编号发号器/事件发布未必跟着回滚。
     */
    @Test
    void rowValidationFailsBeforeAnySupplierIsCreated() {
        SupplierImportConfirmRequest request = new SupplierImportConfirmRequest();
        request.setFileDigest("d".repeat(64));
        request.setIdempotencyKey("import-3");
        SupplierImportPreviewDTO.SupplierRowData bad = new SupplierImportPreviewDTO.SupplierRowData();
        bad.setName("乙供应商"); bad.setContactPerson("王五");
        bad.setPhone("123");                 // 非法手机号
        bad.setAddress("上海市2号");
        request.setRows(List.of(bad));
        when(receiptRepository.findByFactoryIdAndIdempotencyKey("F006", "import-3"))
                .thenReturn(Optional.empty());
        when(receiptRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            SupplierImportReceipt r = invocation.getArgument(0); r.setId("receipt-3"); return r;
        });

        assertThatThrownBy(() -> service.confirm("F006", request, 1309L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("导入行校验失败");

        verifyNoInteractions(supplierService);
    }

    private Map<String, String> row(List<String> headers, String... values) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) row.put(headers.get(i), values[i]);
        return row;
    }
}
