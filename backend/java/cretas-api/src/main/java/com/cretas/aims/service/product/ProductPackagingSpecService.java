package com.cretas.aims.service.product;

import com.cretas.aims.dto.producttype.ProductPackagingSpecDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.product.ProductPackagingSpec;

import java.util.List;

public interface ProductPackagingSpecService {

    List<ProductPackagingSpecDTO> list(String factoryId, String productTypeId);

    List<ProductPackagingSpecDTO> replace(
            ProductType product, List<ProductPackagingSpecDTO> requestedSpecs);

    void synchronizeLegacyDefault(ProductType product);

    PackagingSelection resolveSelection(
            String factoryId, String productTypeId, String transactionUnit, String packagingSpecId);

    /**
     * Resolves a finished-goods opening entry. Opening quantities may be entered
     * in either side of an explicitly selected package specification; normal
     * sales transactions continue to use {@link #resolveSelection}.
     */
    PackagingSelection resolveOpeningSelection(
            String factoryId, String productTypeId, String transactionUnit, String packagingSpecId);

    record PackagingSelection(ProductPackagingSpec spec, boolean required) {
        public static PackagingSelection none() {
            return new PackagingSelection(null, false);
        }
    }
}
