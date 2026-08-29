package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.FactorySettingsDTO;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 采购收货超收上限的<b>工厂级</b>配置读取。
 *
 * <p>只回答一个问题：<b>这家工厂有没有显式配过超收上限？</b>
 * 优先级梯子（工厂级 &gt; 全局属性 &gt; 不限制）不在这里，在
 * {@link OverReceiveLimit#resolve} —— 梯子只能有一份。
 *
 * <h3>配置在哪</h3>
 * {@code FactorySettings.inventorySettings} 这块 JSON 里的
 * {@code purchaseOverReceiveRate}，读写走既有的
 * {@code GET/PUT /api/mobile/{factoryId}/settings/inventory}。
 *
 * <p>⚠️ <b>刻意不与出成报工的 {@code yieldOverReceiptTolerance} 共用</b>。
 * 两者恰好都曾经是 30%，但它们是两条业务规则：一条管"供应商多送了收不收"，
 * 一条管"这道工序产出比标准高多少算异常"。焊成同一个配置项之后，
 * 调一个会连带改另一个，而调的人不会知道。所以：
 * 出成容差在 {@code productionSettings}，采购超收在 {@code inventorySettings}，两块 JSON，两个字段。
 *
 * <p>⚠️ <b>{@code PUT /settings/inventory} 是整块覆盖，不是字段合并</b>
 * （见 {@code FactorySettingsServiceImpl.updateInventorySettings}：直接
 * {@code setInventorySettings(toJsonString(invSettings))}）。前端改这块设置必须
 * load → merge → save；只发一个字段会把同级项连同本上限一起清空
 * —— 清空的后果是回落成"不限制"，不会把货堵在门口，但配置确实丢了。
 *
 * @since 2026-08-25
 */
@Component
public class PurchaseOverReceivePolicy {

    private static final Logger log = LoggerFactory.getLogger(PurchaseOverReceivePolicy.class);

    private final FactorySettingsRepository factorySettingsRepository;
    private final ObjectMapper objectMapper;

    /**
     * 全局属性兜底值（ops 全工厂生效）。<b>留空 = 本层不表态</b>。
     *
     * <p>⚠️ 默认必须是 {@code null} 而不是 {@code 0.00}：用 {@code :0.00} 当默认值时，
     * 「没人配过」和「限制 0%」不可区分，2026-08-25 生产上按 0% 把货堵在了门口。
     *
     * <p>⚠️ 这与 {@code PurchaseServiceImpl} 注入的是<b>同一个 property key</b>，
     * 两处读到的必然是同一个值。之所以在这里也注入一份，是为了让
     * {@link #effectiveLimit(String)} 能被<b>不持有该属性的调用方</b>（如 Workdesk 展示侧）
     * 直接使用 —— 否则展示侧要么拿不到、要么自己再写一份字面量，
     * 而「自己再写一份」正是本次要消灭的那个副本。
     */
    @org.springframework.beans.factory.annotation.Value("${cretas.purchase.over-receive-rate:#{null}}")
    private BigDecimal globalPropertyRate;

    public PurchaseOverReceivePolicy(FactorySettingsRepository factorySettingsRepository,
            ObjectMapper objectMapper) {
        this.factorySettingsRepository = factorySettingsRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 该工厂显式配置的超收上限比率。
     *
     * <p>⚠️ <b>读失败时刻意 fail-open（当作"没配"），并打一条带固定标记的 WARN</b>：
     * 这条路径跑在仓管员点"确认收货"的同步链上，读设置失败就抛 = 供应商车停在门口
     * 谁也收不了货 —— 那正是本次要修的缺陷形态。所以宁可放行并留痕。
     * 排查用 {@code grep 'PURCHASE_OVER_RECEIVE_SETTINGS_UNREADABLE'}。
     *
     * @return {@code Optional.empty()} = 该工厂没配（或读不出来）；否则为已配置的比率（{@code >= 0}）
     */
    public Optional<BigDecimal> factoryConfiguredRate(String factoryId) {
        if (factoryId == null || factoryId.isBlank()) {
            return Optional.empty();
        }
        try {
            String json = factorySettingsRepository.findInventorySettingsByFactoryId(factoryId);
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            FactorySettingsDTO.InventorySettings settings =
                    objectMapper.readValue(json, FactorySettingsDTO.InventorySettings.class);
            BigDecimal rate = settings.getPurchaseOverReceiveRate();
            if (rate == null) {
                return Optional.empty();
            }
            if (rate.signum() < 0) {
                // 负数在 OverReceiveLimit 构造时会抛; 在这里就挡掉, 免得一条脏配置让整个收货入口 500。
                log.warn("PURCHASE_OVER_RECEIVE_SETTINGS_UNREADABLE factoryId={} 超收上限配成了负数 {}, "
                        + "已忽略该配置回落到全局默认", factoryId, rate.toPlainString());
                return Optional.empty();
            }
            return Optional.of(rate);
        } catch (Exception e) {
            log.warn("PURCHASE_OVER_RECEIVE_SETTINGS_UNREADABLE factoryId={} 读工厂库存设置失败, "
                    + "本次按「未配置」处理(不限制超收): {}", factoryId, e.toString());
            return Optional.empty();
        }
    }

    /**
     * 该工厂当前<b>实际生效</b>的超收上限 —— 展示侧应当调这一个方法，
     * 而不是自己再写一份百分比字面量。
     *
     * @param globalPropertyRate 全局属性兜底值（{@code null} = 属性没设）；
     *                           执行侧 {@code PurchaseServiceImpl} 传的就是它注入的那个值，
     *                           保证展示与执行读到的是同一个梯子的同一个结果
     */
    public OverReceiveLimit effectiveLimit(String factoryId, BigDecimal globalPropertyRate) {
        return OverReceiveLimit.resolve(factoryConfiguredRate(factoryId), globalPropertyRate);
    }

    /**
     * 该工厂当前<b>实际生效</b>的超收上限 —— <b>展示侧唯一入口</b>，不需要调用方持有全局属性。
     *
     * <h3>它修的是什么（2026-08-29）</h3>
     * 上面那个双参重载的 javadoc 早就写着「展示侧应当调这一个方法，而不是自己再写一份百分比字面量」，
     * 而 grep 全仓 <b>调用者是 0</b> —— Workdesk 的三个收货工具一直在用自己那份硬编码
     * {@code WorkdeskOverReceivePolicy.OVER_RECEIVE_PCT = 0.30}。
     *
     * <p>后果是<b>展示侧对仓管员说 30%，而执行侧当前根本不限制</b>
     * （{@code cretas.purchase.over-receive-rate} 全仓从未设置过）。
     * 一旦有工厂把它配成 50%，Workdesk 仍然会说 30% —— 两个数，谁也不知道哪个算数。
     *
     * <p>⇒ 正确的 API 早就存在、还写明了用途，只是<b>没有人接上它</b>。
     *
     * @return 非 null；{@code isUnlimited()} 为真时表示不限制，
     *         此时 {@link OverReceiveLimit#maxAllowed} 返回 {@code null}、
     *         {@link OverReceiveLimit#label()} 返回「不限制」。
     *         ⛔ 调用方不许把那个 null 兜底成 0 —— 那会把「不限制」翻译成「一件都不许收」。
     */
    public OverReceiveLimit effectiveLimit(String factoryId) {
        return effectiveLimit(factoryId, globalPropertyRate);
    }
}
