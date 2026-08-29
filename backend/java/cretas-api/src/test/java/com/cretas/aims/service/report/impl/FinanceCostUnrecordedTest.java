package com.cretas.aims.service.report.impl;

import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ShipmentRecordRepository;
import com.cretas.aims.repository.SupplierRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 「成本没录进来」和「这段期间没生产」不是同一个 ¥0。
 *
 * <h3>实测（2026-08-29 真机走查 + 生产库）</h3>
 *
 * 屏上：「¥0 总成本 / ¥0 材料 / ¥0 人工 / ¥0 制造费用」，而<b>同一屏</b>「总产出 8 kg」。
 * 查库：
 *
 * <pre>
 * SELECT count(*), count(actual_material_cost), count(estimated_material_cost),
 *        count(actual_labor_cost) FROM production_plans WHERE factory_id='F006';
 *  → 46 个计划，四个成本列**一个都没填**（全 null）
 * </pre>
 *
 * SUM 于是返 null，被兜成 0 ⇒ 屏上读起来是<b>生产不花钱</b>。
 *
 * <h3>⚠️ 与营收必须区别开 —— 这是这条规则唯一需要判断的地方</h3>
 *
 * <table>
 *   <tr><td>营收 SUM = null</td><td>因为<b>一条出货记录都没有</b>（实测 shipment_records = 0）</td>
 *       <td>没卖就是没收入 ⇒ <b>¥0 正确</b></td></tr>
 *   <tr><td>成本 SUM = null</td><td>却有 <b>46 个计划</b></td>
 *       <td>成本<b>未知</b> ⇒ ⛔ 不是 ¥0</td></tr>
 * </table>
 *
 * ⇒ 判据落在「窗口内有没有生产计划」上，而不是「SUM 是不是 null」。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("财务：成本没录 ≠ 成本为 0")
class FinanceCostUnrecordedTest {

    private static final String FACTORY = "F006";
    private static final LocalDate FROM = LocalDate.of(2026, 8, 1);
    private static final LocalDate TO = LocalDate.of(2026, 8, 29);

    @Mock
    private ProductionPlanRepository productionPlanRepository;
    @Mock
    private ShipmentRecordRepository shipmentRecordRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private SupplierRepository supplierRepository;

    @InjectMocks
    private ProductionReportServiceImpl service;

    private void givenPlansButNoCosts(long planCount) {
        when(productionPlanRepository.countByFactoryIdAndCreatedAtBetween(anyString(), any(), any()))
                .thenReturn(planCount);
        when(productionPlanRepository.calculateMaterialCostBetweenDates(anyString(), any(), any())).thenReturn(null);
        when(productionPlanRepository.calculateLaborCostBetweenDates(anyString(), any(), any())).thenReturn(null);
        when(productionPlanRepository.calculateEquipmentCostBetweenDates(anyString(), any(), any())).thenReturn(null);
        when(productionPlanRepository.calculateOtherCostBetweenDates(anyString(), any(), any())).thenReturn(null);
        when(shipmentRecordRepository.calculateTotalRevenue(anyString(), any(), any())).thenReturn(null);
    }

    @Test
    @DisplayName("有计划、成本列全空 ⇒ 成本 null（这是 F006 的真实形状）")
    void plansExistButCostsUnrecorded() {
        givenPlansButNoCosts(46);

        Map<String, Object> r = service.getFinanceReport(FACTORY, FROM, TO);

        assertThat(r.get("materialCost")).as("材料成本").isNull();
        assertThat(r.get("laborCost")).as("人工成本").isNull();
        assertThat(r.get("equipmentCost")).as("设备成本").isNull();
        assertThat(r.get("otherCost")).as("其他成本").isNull();
        assertThat(r.get("totalCost")).as("总成本").isNull();
        // 成本未知 ⇒ 利润和利润率都算不出来，⛔ 不能变成「利润 = 营收」
        assertThat(r.get("totalProfit")).as("利润").isNull();
        assertThat(r.get("profitMargin")).as("利润率").isNull();
    }

    @Test
    @DisplayName("阴性对照：窗口内没有计划 ⇒ 成本就是 ¥0（没生产确实没花钱）")
    void noPlansMeansZeroCost() {
        givenPlansButNoCosts(0);

        Map<String, Object> r = service.getFinanceReport(FACTORY, FROM, TO);

        assertThat(r.get("materialCost")).isEqualTo(BigDecimal.ZERO);
        assertThat(r.get("totalCost")).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("阴性对照：成本真的录了 ⇒ 照常返回（⛔ 防「一律 null」也能过）")
    void recordedCostsAreReturned() {
        when(productionPlanRepository.countByFactoryIdAndCreatedAtBetween(anyString(), any(), any()))
                .thenReturn(46L);
        when(productionPlanRepository.calculateMaterialCostBetweenDates(anyString(), any(), any()))
                .thenReturn(new BigDecimal("1200.00"));
        when(productionPlanRepository.calculateLaborCostBetweenDates(anyString(), any(), any()))
                .thenReturn(new BigDecimal("300.00"));
        when(shipmentRecordRepository.calculateTotalRevenue(anyString(), any(), any()))
                .thenReturn(new BigDecimal("3000.00"));

        Map<String, Object> r = service.getFinanceReport(FACTORY, FROM, TO);

        assertThat((BigDecimal) r.get("materialCost")).isEqualByComparingTo("1200.00");
        assertThat((BigDecimal) r.get("totalCost")).isEqualByComparingTo("1500.00");
        assertThat((BigDecimal) r.get("totalProfit")).isEqualByComparingTo("1500.00");
        assertThat((Double) r.get("profitMargin")).isEqualTo(50.0);
    }

    @Test
    @DisplayName("阴性对照：营收没有出货记录时仍然是 ¥0 —— 没卖就是没收入，⛔ 不要跟着变 null")
    void revenueStaysZeroWhenNothingShipped() {
        givenPlansButNoCosts(46);

        Map<String, Object> r = service.getFinanceReport(FACTORY, FROM, TO);

        assertThat(r.get("totalRevenue")).isEqualTo(BigDecimal.ZERO);
    }
}
