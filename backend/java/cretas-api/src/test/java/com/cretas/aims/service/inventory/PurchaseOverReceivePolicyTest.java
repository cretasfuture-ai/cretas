package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.FactorySettingsDTO;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 工厂级超收上限的读取 —— 配置侧。
 *
 * <p>守两件事：
 * <ol>
 *   <li>写进 {@code inventorySettings} 的 {@code purchaseOverReceiveRate} 真的读得回来
 *       （⚠️ 不是自己造一个 JSON 串对着自己的解析器验，而是用产品自己的 DTO
 *       序列化再解析，避免"两份口径各自都对但互不兼容"）</li>
 *   <li>缺席 / 脏数据 一律回落成"没配"，⛔ 不许把收货入口打成 500 ——
 *       那等于供应商的车停在门口谁也收不了货</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PurchaseOverReceivePolicy: 工厂级超收上限的读取")
class PurchaseOverReceivePolicyTest {

    private static final String FACTORY_ID = "F002";

    @Mock private FactorySettingsRepository factorySettingsRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PurchaseOverReceivePolicy policy;

    @BeforeEach
    void setUp() {
        policy = new PurchaseOverReceivePolicy(factorySettingsRepository, objectMapper);
    }

    /** 用产品自己的 DTO 生成 JSON —— 存进去和读出来必须是同一套口径。 */
    private String inventorySettingsJson(BigDecimal purchaseOverReceiveRate) throws Exception {
        return objectMapper.writeValueAsString(FactorySettingsDTO.InventorySettings.builder()
                .minStockAlert(100)
                .maxStockLimit(10000)
                .autoReorderPoint(200)
                .purchaseOverReceiveRate(purchaseOverReceiveRate)
                .build());
    }

    @Test
    @DisplayName("⭐ 配了 0.30 → 读回 0.30 (DTO 存进去 DTO 读出来, 同一口径)")
    void configuredRate_roundTrips() throws Exception {
        when(factorySettingsRepository.findInventorySettingsByFactoryId(FACTORY_ID))
                .thenReturn(inventorySettingsJson(new BigDecimal("0.30")));

        Optional<BigDecimal> rate = policy.factoryConfiguredRate(FACTORY_ID);

        assertTrue(rate.isPresent(), "写进去的上限必须读得回来");
        assertEquals(0, new BigDecimal("0.30").compareTo(rate.get()));
    }

    @Test
    @DisplayName("⭐ 显式配 0 → 读回 0 (是配置, ⛔ 不是「没配」)")
    void explicitZero_isConfigured() throws Exception {
        when(factorySettingsRepository.findInventorySettingsByFactoryId(FACTORY_ID))
                .thenReturn(inventorySettingsJson(BigDecimal.ZERO));

        Optional<BigDecimal> rate = policy.factoryConfiguredRate(FACTORY_ID);

        assertTrue(rate.isPresent(),
                "0 被吞成 empty 的话, 「一件都不许多收」这条配置就永远生效不了");
        assertEquals(0, BigDecimal.ZERO.compareTo(rate.get()));
    }

    @Test
    @DisplayName("同一块 JSON 里的其它库存设置项不受影响 (没有把别的字段吃掉)")
    void otherInventorySettingsSurvive() throws Exception {
        String json = inventorySettingsJson(new BigDecimal("0.30"));
        FactorySettingsDTO.InventorySettings parsed =
                objectMapper.readValue(json, FactorySettingsDTO.InventorySettings.class);

        assertEquals(100, parsed.getMinStockAlert());
        assertEquals(10000, parsed.getMaxStockLimit());
        assertEquals(200, parsed.getAutoReorderPoint());
        assertEquals(0, new BigDecimal("0.30").compareTo(parsed.getPurchaseOverReceiveRate()));
    }

    @Test
    @DisplayName("字段缺席 (老工厂的旧 JSON) → empty, 由上层兜成「不限制」")
    void fieldAbsent_isEmpty() {
        when(factorySettingsRepository.findInventorySettingsByFactoryId(FACTORY_ID))
                .thenReturn("{\"minStockAlert\":100,\"maxStockLimit\":10000}");

        assertTrue(policy.factoryConfiguredRate(FACTORY_ID).isEmpty());
    }

    @Test
    @DisplayName("整块设置没有 (工厂从没配过) → empty")
    void noSettingsRow_isEmpty() {
        when(factorySettingsRepository.findInventorySettingsByFactoryId(FACTORY_ID)).thenReturn(null);

        assertTrue(policy.factoryConfiguredRate(FACTORY_ID).isEmpty());
    }

    @Test
    @DisplayName("⭐ JSON 是脏的 → empty + 不抛 (收货入口不能被一条脏配置打成 500)")
    void unparseableJson_failsOpen() {
        when(factorySettingsRepository.findInventorySettingsByFactoryId(FACTORY_ID))
                .thenReturn("{ 这不是 JSON ");

        Optional<BigDecimal> rate = assertDoesNotThrow(() -> policy.factoryConfiguredRate(FACTORY_ID));
        assertTrue(rate.isEmpty());
    }

    @Test
    @DisplayName("⭐ 配成负数 → 忽略该配置 + 不抛 (否则 OverReceiveLimit 构造会把收货入口打成 500)")
    void negativeRate_isIgnored() throws Exception {
        when(factorySettingsRepository.findInventorySettingsByFactoryId(FACTORY_ID))
                .thenReturn(inventorySettingsJson(new BigDecimal("-0.10")));

        Optional<BigDecimal> rate = assertDoesNotThrow(() -> policy.factoryConfiguredRate(FACTORY_ID));
        assertTrue(rate.isEmpty(), "负数是脏配置, 应当被忽略而不是原样往下传");
    }

    @Test
    @DisplayName("factoryId 为空 → empty, 不打库")
    void blankFactoryId_isEmpty() {
        assertTrue(policy.factoryConfiguredRate(null).isEmpty());
        assertTrue(policy.factoryConfiguredRate("  ").isEmpty());
    }

    @Test
    @DisplayName("effectiveLimit 走的是同一条梯子: 工厂级 > 属性 > 不限制")
    void effectiveLimit_usesTheSameLadder() throws Exception {
        when(factorySettingsRepository.findInventorySettingsByFactoryId(FACTORY_ID))
                .thenReturn(inventorySettingsJson(new BigDecimal("0.15")));

        OverReceiveLimit limit = policy.effectiveLimit(FACTORY_ID, new BigDecimal("0.99"));

        assertEquals(OverReceiveLimit.SOURCE_FACTORY_SETTINGS, limit.source());
        assertEquals("15%", limit.label());
    }

    @Test
    @DisplayName("工厂没配 + 属性也没设 → 不限制")
    void effectiveLimit_nothingAnywhere_isUnlimited() {
        when(factorySettingsRepository.findInventorySettingsByFactoryId(FACTORY_ID)).thenReturn(null);

        OverReceiveLimit limit = policy.effectiveLimit(FACTORY_ID, null);

        assertTrue(limit.isUnlimited());
        assertEquals(OverReceiveLimit.SOURCE_UNCONFIGURED, limit.source());
    }
}
