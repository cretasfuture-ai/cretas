package com.cretas.aims.service.report.impl;

import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 🔒 库存报表里 <b>materialTypeId 为 null 的批次</b>（半成品/WIP）不许把整个接口炸掉。
 *
 * <p>`sumQuantityByMaterialType` 按 {@code m.materialTypeId} 分组，而 WIP 批次那一列是 null
 * （它们用 {@code productTypeId}）。原实现把它当键直接塞进 {@code HashMap}，
 * Java 侧不报错，**炸在序列化那一步**：</p>
 *
 * <pre>HttpMessageNotWritableException: Null key for a Map not allowed in JSON</pre>
 *
 * <p><b>生产实测（2026-08-27）</b>：F006 的 64 条 AVAILABLE 批次里 14 条 materialTypeId 为 null
 * （14 条全部有 productTypeId），MOCK_REST / LIUSHANMEN 各 0 条 ——
 * 于是 {@code GET /reports/inventory} 恰好<b>只对真客户租户返回 500</b>。
 * 拿 f006_admin 实打 22 个报表端点，21 个返回真数据，坏的就这一个。</p>
 *
 * <p>⚠️ 本测试<b>真的做一次 JSON 序列化</b>。只断言「方法不抛异常」是不够的 ——
 * 缺陷本来就不在方法里，方法一直好好地返回；炸的是下游那一步。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductionReportInventoryNullTypeTest {

    @Mock
    MaterialBatchRepository materialBatchRepository;

    @InjectMocks
    ProductionReportServiceImpl service;

    private static final String FACTORY = "F006";

    /** 复刻线上形状：一条正常原料 + 一条 materialTypeId 为 null 的 WIP。 */
    private void stubInventory() {
        when(materialBatchRepository.countByFactoryId(FACTORY)).thenReturn(64L);
        when(materialBatchRepository.calculateInventoryValue(FACTORY)).thenReturn(new BigDecimal("1000.00"));
        when(materialBatchRepository.countByFactoryIdAndStatus(anyString(), any(MaterialBatchStatus.class)))
                .thenReturn(0L);
        when(materialBatchRepository.sumQuantityByMaterialType(FACTORY)).thenReturn(List.<Object[]>of(
                new Object[]{"MT-0001", new BigDecimal("50.000000")},
                new Object[]{null, new BigDecimal("14.000000")}   // ← WIP，线上就是这个形状
        ));
        when(materialBatchRepository.findExpiringBatches(anyString(), any(LocalDate.class)))
                .thenReturn(List.of());
        when(materialBatchRepository.findExpiredBatches(anyString())).thenReturn(List.of());
        when(materialBatchRepository.findLowStockMaterials(anyString())).thenReturn(List.of());
    }

    @Test
    void inventoryReport_withNullMaterialType_isJsonSerializable() throws Exception {
        stubInventory();
        Map<String, Object> report = service.getInventoryReport(FACTORY, LocalDate.of(2026, 8, 27));

        // 🔴 要害在这一行：缺陷不在方法里，方法一直正常返回，炸的是序列化。
        assertThatCode(() -> new ObjectMapper().writeValueAsString(report))
                .as("materialTypeId 为 null 的 WIP 批次不许让整张报表序列化失败")
                .doesNotThrowAnyException();
    }

    @Test
    void nullMaterialTypeQuantity_isReportedSeparately_notDropped() throws Exception {
        stubInventory();
        Map<String, Object> report = service.getInventoryReport(FACTORY, LocalDate.of(2026, 8, 27));

        @SuppressWarnings("unchecked")
        Map<String, BigDecimal> dist = (Map<String, BigDecimal>) report.get("typeDistribution");

        // 正常那条还在
        assertThat(dist).containsKey("MT-0001");
        // ⛔ null 键不许出现（它就是炸点）
        assertThat(dist).doesNotContainKey(null);

        // 🔴 阴性对照：**量不许被吞掉**。只把 null 那行 skip 掉也能让上面两条通过，
        //    但那样 14 的库存就凭空消失、报表加起来对不上 —— 那是另一种缺陷。
        assertThat((BigDecimal) report.get("quantityWithoutMaterialType"))
                .as("WIP 的量必须显式记下来, ⛔ 不许静默丢弃")
                .isEqualByComparingTo(new BigDecimal("14.000000"));
    }

    @Test
    void noNullTypeRows_meansNoLoss_andEmptyBucket() {
        // ⛔ 阴性对照 ②：别把「有 WIP 就记一笔」做成「永远记一笔非零」。
        when(materialBatchRepository.countByFactoryId(FACTORY)).thenReturn(24L);
        when(materialBatchRepository.calculateInventoryValue(FACTORY)).thenReturn(BigDecimal.TEN);
        when(materialBatchRepository.countByFactoryIdAndStatus(anyString(), any(MaterialBatchStatus.class)))
                .thenReturn(0L);
        when(materialBatchRepository.sumQuantityByMaterialType(FACTORY)).thenReturn(List.<Object[]>of(
                new Object[]{"MT-0001", new BigDecimal("50.000000")}
        ));
        when(materialBatchRepository.findExpiringBatches(anyString(), any(LocalDate.class)))
                .thenReturn(List.of());
        when(materialBatchRepository.findExpiredBatches(anyString())).thenReturn(List.of());
        when(materialBatchRepository.findLowStockMaterials(anyString())).thenReturn(List.of());

        Map<String, Object> report = service.getInventoryReport(FACTORY, LocalDate.of(2026, 8, 27));
        assertThat((BigDecimal) report.get("quantityWithoutMaterialType"))
                .as("没有 WIP 行时这一格必须是 0 —— 它是读数不是占位")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }
}
