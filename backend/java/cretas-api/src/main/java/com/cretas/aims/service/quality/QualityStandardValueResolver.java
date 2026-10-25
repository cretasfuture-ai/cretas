package com.cretas.aims.service.quality;

/** Resolves product-binding overrides without treating blank text as a value. */
public final class QualityStandardValueResolver {

    private QualityStandardValueResolver() {
    }

    public static String inheritBlank(String overrideValue, String itemValue) {
        return overrideValue == null || overrideValue.isBlank() ? itemValue : overrideValue;
    }

}
