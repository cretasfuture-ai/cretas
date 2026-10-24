package com.cretas.aims.service.consent;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import org.springframework.stereotype.Component;

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
        if (factoryId == null || factoryId.isBlank()) {
            return false;
        }
        Set<String> protectedIds = accessService.protectedProductTypeIds(factoryId);
        if (protectedIds.isEmpty()) {
            return false;
        }
        List<ProductType> products = productTypeRepository.findAllById(protectedIds);
        for (ProductType product : products) {
            String name = product.getName();
            if (!factoryId.equals(product.getFactoryId()) || name == null || name.trim().length() < MIN_NAME_LENGTH) {
                continue;
            }
            for (String text : texts) {
                if (text != null && text.contains(name.trim())) {
                    return true;
                }
            }
        }
        return false;
    }
}
