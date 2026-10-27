package com.cretas.aims.service.consent;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 隐私条款第六条: 工艺保密产品的数据不用于训练。训练样本不带产品编号, 所以按名称判断:
 * 原话 / 问答里出现该厂任一保密产品的名称, 整条不进训练样本。名称少于 2 个字的不参与匹配
 * (避免单字误伤)。与 Python {@code smartbi/services/process_secret_names.py} 同一规则。
 */
@Component
public class ProcessSecretSampleFilter {

    static final int MIN_NAME_LENGTH = 2;

    private final ProcessSecretAccessService accessService;
    private final ProductTypeRepository productTypeRepository;

    public ProcessSecretSampleFilter(ProcessSecretAccessService accessService,
                                     ProductTypeRepository productTypeRepository) {
        this.accessService = accessService;
        this.productTypeRepository = productTypeRepository;
    }

    /** 文本是否提到本厂某个工艺保密产品。 */
    public boolean mentionsProtectedProduct(String factoryId, String... texts) {
        for (String name : protectedProductNames(factoryId)) {
            for (String text : texts) {
                if (text != null && text.contains(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 本厂工艺保密产品名 (只含本厂、长度够的名字; 已 trim)。 */
    public Set<String> protectedProductNames(String factoryId) {
        if (factoryId == null || factoryId.isBlank()) {
            return Set.of();
        }
        Set<String> protectedIds = accessService.protectedProductTypeIds(factoryId);
        if (protectedIds.isEmpty()) {
            return Set.of();
        }
        // 一条消息要过好几处外发出口, 每处都要这份名单: 按「工厂 + 当前保密产品集合」缓存 10 秒。
        // 保密产品集合来自 accessService (改保密设置时立即失效), 集合一变键就变 —— 新设保密立即生效;
        // 只有「已保密产品改名」最多 10 秒后才认新名字。查库失败不缓存, 照样抛给调用方 (外发据此拒绝)。
        return nameCache.get(new NameKey(factoryId, Set.copyOf(protectedIds)), key -> loadNames(factoryId, protectedIds));
    }

    static final Duration NAME_CACHE_TTL = Duration.ofSeconds(10);

    private record NameKey(String factoryId, Set<String> protectedIds) {
    }

    private final Cache<NameKey, Set<String>> nameCache = Caffeine.newBuilder()
            .expireAfterWrite(NAME_CACHE_TTL)
            .maximumSize(1_000)
            .build();

    private Set<String> loadNames(String factoryId, Set<String> protectedIds) {
        Set<String> names = new LinkedHashSet<>();
        List<ProductType> products = productTypeRepository.findAllById(protectedIds);
        for (ProductType product : products) {
            String name = product.getName();
            if (!factoryId.equals(product.getFactoryId()) || name == null || name.trim().length() < MIN_NAME_LENGTH) {
                continue;
            }
            names.add(name.trim());
        }
        return Set.copyOf(names);
    }
}
