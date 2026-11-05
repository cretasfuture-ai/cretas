package com.cretas.aims.util;

import com.cretas.aims.entity.config.FactoryTemplate;
import java.util.Collection;
import java.util.Map;
import java.util.Locale;

/** Prevent historical restaurant templates from restoring retired modules. */
public final class RetiredTemplatePolicy {
    private RetiredTemplatePolicy() {}

    public static boolean isRetiredModule(String code) {
        return code != null && code.trim().toLowerCase(Locale.ROOT).startsWith("restaurant");
    }

    public static boolean isRetired(FactoryTemplate template) {
        if (isRetiredModule(template.getTemplateCode())
                || BusinessDomainUtils.RESTAURANT.equals(BusinessDomainUtils.resolveDomain(template.getIndustryType()))) {
            return true;
        }
        Map<String, Object> base = template.getBaseConfig();
        if (base != null && base.get("moduleConfigs") instanceof Map<?, ?> modules
                && modules.get("enabledModules") instanceof Collection<?> enabled) {
            if (enabled.stream().anyMatch(code -> code instanceof String s && isRetiredModule(s))) return true;
        }
        if (base != null && base.get("seedDynamicFields") instanceof Collection<?> seeds) {
            return seeds.stream().anyMatch(seed -> seed instanceof Map<?, ?> fields
                    && fields.get("moduleCode") instanceof String code && isRetiredModule(code));
        }
        return false;
    }
}
