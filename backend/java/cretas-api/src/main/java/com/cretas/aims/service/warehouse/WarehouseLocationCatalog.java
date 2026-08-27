package com.cretas.aims.service.warehouse;

import com.cretas.aims.entity.FactoryFeatureConfig;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactoryFeatureConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 本厂 {@code WAREHOUSE_LAYOUT} 配置里已存在的储存位置编码 —— <b>唯一实现</b>。
 *
 * <h4>为什么单独一个类</h4>
 *
 * <p>这段判据原本是 {@code WarehouseLayoutController} 的私有方法，只守着成品移位
 * 那一条路。2026-08-27 实测全后端 <b>8 处</b> {@code setStorageLocation}，其中
 * <b>只有 1 处</b>（成品移位）过了这道判据；另外 6 处直接写实体，1 处是 AI 工具写 DTO。
 * 把判据复制到第二个调用点就是形态 D（同一个东西两份，它一定会漂）⇒ 收敛到这里。</p>
 *
 * <h4>产品裁定（2026-08-27，Owner）：储存位置是受控词表</h4>
 *
 * <p>「受控 一定得是具体的位置 有编号的呀」——
 * 位置字段唯一的真实消费方是 web-admin「仓储空间」页的
 * {@code countAt(code) = batches.filter(b => b.location === code)}（<b>精确字符串相等</b>），
 * 位置卡上显示「X 批 / Y 种物料 / 空闲」。</p>
 *
 * <p>⇒ 一旦写进「冷冻库」这种自由文本，那条批次就从<b>所有</b>位置的计数里消失，
 * 卡片显示「空闲」而货其实在那儿。<b>不可信的计数比没有计数更糟</b> ——
 * 它让人以为那个位置是空的。所以这个字段只有在是受控编码时才有意义。</p>
 *
 * <h4>null 是合法的，空串不是</h4>
 *
 * <p>{@code null} = <b>未分配</b>，这是合法状态（2026-08-27 生产实测：F006 的 139 条
 * 原料批次 {@code storage_location} 全部为 NULL）。⛔ 不要把「没填」当成缺陷去拦，
 * 那会把一个正常的业务状态变成错误。</p>
 *
 * <p>要拦的是<b>填了、但填的不是一个位置</b>。</p>
 *
 * <h4>fail closed</h4>
 *
 * <p>配置不存在时返回空集 —— 也就是「一个位置都没配」时任何赋位置都会被拒。
 * 这是有意的：宁可挡住，也不要再往 {@code storage_location} 里塞一个谁也解释不了的
 * 字符串（2026-08-25 生产上真出现过 "Workflow terminal output warehouse receipt"
 * 这种来源描述躺在位置字段里）。</p>
 */
@Service
@RequiredArgsConstructor
public class WarehouseLocationCatalog {

    /** 与 {@code WarehouseLayoutController} 用的是同一个 module id。 */
    public static final String MODULE_ID = "WAREHOUSE_LAYOUT";

    private final FactoryFeatureConfigRepository featureConfigRepository;

    /**
     * 本厂已配置的位置编码集合；配置缺失或结构不对时返回空集（fail closed）。
     */
    @SuppressWarnings("unchecked")
    public Set<String> configuredCodes(String factoryId) {
        Map<String, Object> config = featureConfigRepository
                .findByFactoryIdAndModuleIdAndDeletedAtIsNull(factoryId, MODULE_ID)
                .map(FactoryFeatureConfig::getConfig)
                .orElse(Map.of());
        Object locations = config.get("locations");
        if (!(locations instanceof List<?> locationList)) {
            return Set.of();
        }
        Set<String> codes = new HashSet<>();
        for (Object entry : locationList) {
            if (entry instanceof Map<?, ?> map) {
                Object code = map.get("code");
                if (code != null && !String.valueOf(code).isBlank()) {
                    codes.add(String.valueOf(code).trim());
                }
            }
        }
        return codes;
    }

    /**
     * 校验并归一化一个待写入的储存位置。
     *
     * @param raw 用户/上游传来的原始值；{@code null} 表示不改动或未分配
     * @return {@code null}（未分配）或已 trim 且确认存在于本厂配置中的编码
     * @throws BusinessException 400 —— 填了但不是本厂已配置的位置
     */
    public String requireConfiguredLocation(String factoryId, String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            // 显式清空 = 回到「未分配」，这是允许的
            return null;
        }
        if (trimmed.length() > 100) {
            throw new BusinessException(400, "请选择有效的储存位置");
        }
        if (!configuredCodes(factoryId).contains(trimmed)) {
            throw new BusinessException(400,
                    "储存位置 " + trimmed + " 不在本厂仓储配置中，请先在仓储空间中创建该位置");
        }
        return trimmed;
    }
}
