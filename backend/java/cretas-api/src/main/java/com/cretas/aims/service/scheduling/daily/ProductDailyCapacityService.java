package com.cretas.aims.service.scheduling.daily;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 产品人工日产能的唯一写入口 (排程页当场补填 + 产品页编辑共用)。
 * 产品必须属于本厂 (按 factoryId 查, 跨厂 404 且不写); 数值 &gt; 0、单位非空, 成对写入。
 */
@Service
@RequiredArgsConstructor
public class ProductDailyCapacityService {

    static final int MAX_UNIT_LENGTH = 32;

    private final ProductTypeRepository productTypeRepository;

    /** 先查后写: 产品不在本厂直接 404。用于批量写之前先把整批校验完, 不留半截。 */
    @Transactional(readOnly = true)
    public ProductType requireOwnProduct(String factoryId, String productTypeId) {
        if (productTypeId == null || productTypeId.isBlank()) {
            throw new BusinessException(400, "产品不能为空");
        }
        return productTypeRepository.findByIdAndFactoryId(productTypeId.trim(), factoryId)
                .orElseThrow(() -> new BusinessException(404, "产品不存在或不属于当前工厂: " + productTypeId)
                        .withCode("PRODUCT_NOT_FOUND"));
    }

    /** 校验一个日产能值 (不写库); 返回规整后的数值 (2 位小数)。 */
    public static BigDecimal normalizeQuantity(BigDecimal quantityPerDay) {
        if (quantityPerDay == null) {
            throw new BusinessException(400, "日产能不能为空").withHintTarget("quantityPerDay");
        }
        BigDecimal scaled = quantityPerDay.setScale(2, RoundingMode.HALF_UP);
        if (scaled.signum() <= 0) {
            throw new BusinessException(400, "日产能必须大于 0").withHintTarget("quantityPerDay");
        }
        if (scaled.precision() - scaled.scale() > 10) {
            throw new BusinessException(400, "日产能数值过大").withHintTarget("quantityPerDay");
        }
        return scaled;
    }

    public static String normalizeUnit(String unit) {
        if (unit == null || unit.isBlank()) {
            throw new BusinessException(400, "日产能单位不能为空").withHintTarget("unit");
        }
        String trimmed = unit.trim();
        if (trimmed.length() > MAX_UNIT_LENGTH) {
            throw new BusinessException(400, "日产能单位过长").withHintTarget("unit");
        }
        return trimmed;
    }

    /** 写入 (或覆盖) 一个产品的人工日产能。 */
    @Transactional
    public ProductType save(String factoryId, String productTypeId, BigDecimal quantityPerDay, String unit,
                            Long userId) {
        BigDecimal quantity = normalizeQuantity(quantityPerDay);
        String normalizedUnit = normalizeUnit(unit);
        ProductType product = requireOwnProduct(factoryId, productTypeId);
        product.setDailyCapacity(quantity);
        product.setDailyCapacityUnit(normalizedUnit);
        product.setDailyCapacityUpdatedAt(LocalDateTime.now());
        product.setDailyCapacityUpdatedBy(userId);
        return productTypeRepository.saveAndFlush(product);
    }

    /** 清掉一个产品的人工日产能 (回到「没填」: 排程改用经验值或当场问)。 */
    @Transactional
    public ProductType clear(String factoryId, String productTypeId, Long userId) {
        ProductType product = requireOwnProduct(factoryId, productTypeId);
        product.setDailyCapacity(null);
        product.setDailyCapacityUnit(null);
        product.setDailyCapacityUpdatedAt(LocalDateTime.now());
        product.setDailyCapacityUpdatedBy(userId);
        return productTypeRepository.saveAndFlush(product);
    }
}
