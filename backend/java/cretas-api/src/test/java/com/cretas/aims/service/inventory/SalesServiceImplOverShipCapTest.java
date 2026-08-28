package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.service.canvas.ThresholdKeys;
import com.cretas.aims.service.canvas.ThresholdResolverService;
import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 销售发货【超发上限】闸的钉子。
 *
 * <h2>它守的行为</h2>
 * 仓管在 {@code warehouseConfirmDelivery} 覆盖实发数量时，
 * 实发不得超过 {@code 计划量 × (1 + 上限比率)}。
 *
 * <p>2026-08-28 生产实测走通过一次「订 2 发 5」：<b>库存真扣 5 件、货真的出门、订单被置为
 * COMPLETED</b>。当时那条路径上只有一句 {@code if (actual.signum() < 0)}。
 *
 * <h2>⛔ 为什么断言分成两层</h2>
 * <ul>
 *   <li>{@link CapArithmetic} —— 打在<b>判据本身</b>上（纯函数，能穷举边界）</li>
 *   <li>{@link LadderResolution} —— 打在<b>取值梯子</b>上（配置从哪来、读不到怎么办）</li>
 * </ul>
 * 只测其中一层会漏掉最常见的两种坏法：判据对但读不到配置（闸恒不触发），
 * 或读得到配置但判据写反（闸拦错人）。
 *
 * <h2>本类自带的阴性对照</h2>
 * 每条「该拦」都配一条「不该拦」，且 {@link #unlimitedLetsAnythingThrough()} 证明
 * <b>去掉配置这道闸就完全不生效</b> —— 否则「测试全绿」可能只是因为它谁都拦。
 */
class SalesServiceImplOverShipCapTest {

    /** 反射调私有闸；本闸是纯判断，不需要搭 warehouseConfirmDelivery 的一长串仓储桩。 */
    private void assertCap(SalesServiceImpl svc, SalesDeliveryItem item,
            BigDecimal actual, OverReceiveLimit limit) {
        ReflectionTestUtils.invokeMethod(svc, "assertWithinOverShipCap", item, "42", actual, limit);
    }

    private SalesServiceImpl bareService() {
        // CALLS_REAL_METHODS: 只跑被测的那个私有方法, 不触发任何依赖
        return mock(SalesServiceImpl.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    }

    private SalesDeliveryItem line(String plannedQty) {
        SalesDeliveryItem item = new SalesDeliveryItem();
        ReflectionTestUtils.setField(item, "id", 42L);
        item.setDeliveredQuantity(plannedQty == null ? null : new BigDecimal(plannedQty));
        item.setUnit("盒");
        return item;
    }

    // ==================================================================
    @Nested
    @DisplayName("判据本身: 实发 ≤ 计划 × (1 + 上限)")
    class CapArithmetic {

        private final OverReceiveLimit tenPercent =
                OverReceiveLimit.of(new BigDecimal("0.10"), OverReceiveLimit.SOURCE_SALES_THRESHOLD_HUB);

        @Test
        @DisplayName("🔴 生产实测那一单: 计划 2 实发 5 @10% → 拦下 (上限 2.2)")
        void theProductionCase_isRejected() {
            assertThatThrownBy(() -> assertCap(bareService(), line("2"), new BigDecimal("5"), tenPercent))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("超出超发上限")
                    .hasMessageContaining("计划 2盒")
                    .hasMessageContaining("实发 5盒")
                    .hasMessageContaining("最大可发 2.2盒")
                    .hasMessageContaining("含 10% 超发");
        }

        @Test
        @DisplayName("阴性对照: 实发 == 计划 → 放行 (正常确认不能被这道闸误伤)")
        void exactQuantity_passes() {
            assertThatCode(() -> assertCap(bareService(), line("2"), new BigDecimal("2"), tenPercent))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("阴性对照: 少发 (实发 < 计划) → 放行, 这道闸只管上限")
        void underShipment_passes() {
            assertThatCode(() -> assertCap(bareService(), line("10"), new BigDecimal("2"), tenPercent))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("边界: 恰好等于上限 (计划 10 → 11.0) 放行; 再多一点 (11.01) 拦下")
        void boundaryIsInclusive() {
            assertThatCode(() -> assertCap(bareService(), line("10"), new BigDecimal("11.0"), tenPercent))
                    .as("恰好 110% 是允许的 —— ⛔ 用 < 而不是 <= 会把边界上的正常发货拦掉")
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> assertCap(bareService(), line("10"), new BigDecimal("11.01"), tenPercent))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("🔴 rate=0 是真实限制(一件都不许超发), ⛔ 不等于「不限制」")
        void zeroRateIsARealLimit() {
            OverReceiveLimit zero =
                    OverReceiveLimit.of(BigDecimal.ZERO, OverReceiveLimit.SOURCE_SALES_THRESHOLD_HUB);
            assertThatCode(() -> assertCap(bareService(), line("2"), new BigDecimal("2"), zero))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> assertCap(bareService(), line("2"), new BigDecimal("2.01"), zero))
                    .as("0 必须拦住任何超发 —— 若它被当成「不限制」, 采购侧那个缺陷就在销售侧复现了")
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("🔴 阴性对照: 不限制 → 什么都拦不住 (证明这道闸确实由配置驱动)")
        void unlimitedLetsAnythingThrough() {
            OverReceiveLimit unlimited =
                    OverReceiveLimit.unlimited(OverReceiveLimit.SOURCE_SALES_UNCONFIGURED);
            assertThatCode(() -> assertCap(bareService(), line("2"), new BigDecimal("99999"), unlimited))
                    .as("没有这条对照, 「其它用例全绿」也可能只是因为这道闸对谁都放行")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("计划量为 null → 放行, ⛔ 不许兜底成 0 (那会把「不知道」判成「一件都不许发」)")
        void nullPlannedQuantity_passes() {
            assertThatCode(() -> assertCap(bareService(), line(null), new BigDecimal("5"), tenPercent))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("limit 为 null (调用方忘了解析) → 放行, 不把闸变成崩溃点")
        void nullLimit_passes() {
            assertThatCode(() -> assertCap(bareService(), line("2"), new BigDecimal("5"), null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("异常必须带 code 和可执行的 hint, 且 hint 指向真实存在的配置入口")
        void exceptionCarriesActionableHint() {
            assertThatThrownBy(() -> assertCap(bareService(), line("2"), new BigDecimal("5"), tenPercent))
                    .isInstanceOfSatisfying(BusinessException.class, ex -> {
                        // ⚠️ getCode() 是 HTTP 码(Integer), 语义码在 getErrorCode() —— 别搞反
                        assertThat(ex.getCode())
                                .as("必须是 409 冲突, 不是 400 参数错 —— 数量本身合法, 是与业务规则冲突")
                                .isEqualTo(409);
                        assertThat(ex.getErrorCode()).isEqualTo("DELIVERY_QUANTITY_EXCEEDS_OVER_SHIP_CAP");
                        assertThat(ex.getActionHint())
                                .as("仓管看到 409 之后要知道下一步做什么")
                                .contains("按实际装车量填写")
                                .contains("业务阈值配置");
                        assertThat(ex.getHintTarget()).isEqualTo("actualQuantities");
                    });
        }

        @Test
        @DisplayName("⛔ 文案里的百分比必须由 limit 插值, 不许硬编码 —— 换成 25% 文案要跟着变")
        void percentInMessageComesFromTheLimit() {
            OverReceiveLimit quarter =
                    OverReceiveLimit.of(new BigDecimal("0.25"), OverReceiveLimit.SOURCE_SALES_THRESHOLD_HUB);
            assertThatThrownBy(() -> assertCap(bareService(), line("4"), new BigDecimal("6"), quarter))
                    .hasMessageContaining("含 25% 超发")
                    .hasMessageContaining("最大可发 5盒")
                    .hasMessageNotContaining("10%");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("取值梯子: 阈值中心 > 全局属性 > 不限制")
    class LadderResolution {

        private OverReceiveLimit resolve(SalesServiceImpl svc) {
            return (OverReceiveLimit) ReflectionTestUtils.invokeMethod(
                    svc, "resolveOverShipLimit", "F006");
        }

        private SalesServiceImpl serviceWith(ThresholdResolverService hub, String propertyRate) {
            SalesServiceImpl svc = mock(SalesServiceImpl.class, org.mockito.Mockito.CALLS_REAL_METHODS);
            ReflectionTestUtils.setField(svc, "thresholdResolverService", hub);
            ReflectionTestUtils.setField(svc, "salesOverShipRateProperty",
                    propertyRate == null ? null : new BigDecimal(propertyRate));
            return svc;
        }

        private ThresholdResolverService hubReturning(String rate) {
            ThresholdResolverService hub = mock(ThresholdResolverService.class);
            when(hub.getBigDecimal(eq("F006"), eq(ThresholdKeys.SALES_DELIVERY_OVER_SHIP_RATE), any()))
                    .thenReturn(rate == null ? null : new BigDecimal(rate));
            return hub;
        }

        @Test
        @DisplayName("阈值中心有值 → 用它, 且来源标记说的是阈值中心")
        void hubWins() {
            OverReceiveLimit limit = resolve(serviceWith(hubReturning("0.25"), "0.50"));
            assertThat(limit.rate()).isEqualByComparingTo("0.25");
            assertThat(limit.source())
                    .as("来源不能说谎 —— 排查时全靠这一行判断读数从哪来")
                    .isEqualTo(OverReceiveLimit.SOURCE_SALES_THRESHOLD_HUB);
        }

        @Test
        @DisplayName("阈值中心读不到 → 回落全局属性, 来源标记跟着变")
        void fallsBackToProperty() {
            OverReceiveLimit limit = resolve(serviceWith(hubReturning(null), "0.50"));
            assertThat(limit.rate()).isEqualByComparingTo("0.50");
            assertThat(limit.source()).isEqualTo(OverReceiveLimit.SOURCE_SALES_PROPERTY);
        }

        @Test
        @DisplayName("🔴 两处都没有 → 不限制, ⛔ 不是限制 0%")
        void nothingConfigured_isUnlimited() {
            OverReceiveLimit limit = resolve(serviceWith(hubReturning(null), null));
            assertThat(limit.isUnlimited())
                    .as("「没人配过」必须等于「不限制」—— 兜底成 0 就是把货堵在门口, "
                            + "采购侧 2026-08-25 正是这么坏的")
                    .isTrue();
            assertThat(limit.source()).isEqualTo(OverReceiveLimit.SOURCE_SALES_UNCONFIGURED);
        }

        @Test
        @DisplayName("阈值中心没注入(单测/降级) → 不限制, 不 NPE")
        void noResolverInjected_isUnlimited() {
            OverReceiveLimit limit = resolve(serviceWith(null, null));
            assertThat(limit.isUnlimited()).isTrue();
        }

        @Test
        @DisplayName("🔴 阈值中心配了 0 → 必须真的限制到 0%, 不能被读成「没配」")
        void hubZero_isARealLimit() {
            OverReceiveLimit limit = resolve(serviceWith(hubReturning("0"), null));
            assertThat(limit.isUnlimited())
                    .as("0 与 null 必须泾渭分明 —— 这正是 OverReceiveLimit 存在的理由")
                    .isFalse();
            assertThat(limit.rate()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("坏配置(负数) → 按不限制放行并留痕, ⛔ 不抛异常把发货全堵死")
        void negativeConfig_degradesToUnlimited() {
            OverReceiveLimit limit = resolve(serviceWith(hubReturning("-0.10"), null));
            assertThat(limit.isUnlimited())
                    .as("一条坏配置不该让整个工厂发不了货; 但必须打 WARN 留痕")
                    .isTrue();
        }

        @Test
        @DisplayName("全局属性是负数 → 同样不采信, 退到不限制")
        void negativeProperty_isIgnored() {
            OverReceiveLimit limit = resolve(serviceWith(hubReturning(null), "-1"));
            assertThat(limit.isUnlimited()).isTrue();
        }
    }
}
