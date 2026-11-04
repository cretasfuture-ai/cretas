package com.cretas.aims.service.datacenter.impl;

import com.cretas.aims.entity.datacenter.ExportRule;

import java.util.List;
import java.util.Map;

/** 给别的包里的保密配置测试用: 导出裁剪 {@link ExportExecutor#maskProcessSecret} 是包内可见的。 */
public final class SecrecyExportTestHooks {

    private SecrecyExportTestHooks() {
    }

    public static List<Map<String, Object>> mask(ExportExecutor executor, ExportRule rule, List<Map<String, Object>> rows) {
        return executor.maskProcessSecret(rule, rows);
    }
}
