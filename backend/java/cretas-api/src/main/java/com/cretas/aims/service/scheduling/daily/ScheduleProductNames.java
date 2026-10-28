package com.cretas.aims.service.scheduling.daily;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.enums.ProductCategory;
import com.cretas.aims.repository.RawMaterialTypeRepository;

import java.util.Optional;

/**
 * 排程页上产品的显示名 —— 补日产能面板和待排产计划列表共用这一份 (⛔ 不要再各写一份)。
 *
 * <p>联产计划的主产品是一个原料归属产品, 名字形如 {@code __RAW_WORKFLOW_OWNER__RMT_…}:
 * 显示对应原料的名字 (如「LY-FX-安格斯65碎肉」), 查不到就去掉前缀。
 */
public final class ScheduleProductNames {

    public static final String RAW_OWNER_PREFIX = "__RAW_WORKFLOW_OWNER__";

    private ScheduleProductNames() {
    }

    public static String displayName(RawMaterialTypeRepository rawMaterialTypeRepository, String factoryId,
                                     ProductType product) {
        String name = product.getName();
        boolean rawAnchor = (name != null && name.startsWith(RAW_OWNER_PREFIX))
                || ProductCategory.RAW_MATERIAL.equals(product.getProductCategory())
                || ProductCategory.RAW_MATERIAL.equals(product.getCategory());
        if (rawAnchor) {
            Optional<String> rawName = rawMaterialTypeRepository == null ? Optional.empty()
                    : rawMaterialTypeRepository.findByIdAndFactoryId(product.getId(), factoryId)
                            .map(r -> r.getName());
            if (rawName.isPresent() && !rawName.get().isBlank()) {
                return rawName.get();
            }
            if (name != null && name.startsWith(RAW_OWNER_PREFIX)) {
                return name.substring(RAW_OWNER_PREFIX.length());
            }
        }
        return name != null ? name : product.getId();
    }
}
