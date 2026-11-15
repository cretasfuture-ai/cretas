package com.cretas.aims.service.integration;

import java.util.Arrays;
import java.util.Optional;

/**
 * 能授权的外部软件。取值与迁移 V20261115_51 的 CHECK 一致 (ExternalIntegrationMigrationPostgresContractTest 核对)。
 */
public enum ExternalSystem {
    BLACKLAKE("blacklake", "黑湖");

    private final String code;
    private final String label;

    ExternalSystem(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() { return code; }

    public String label() { return label; }

    public static Optional<ExternalSystem> of(String code) {
        return Arrays.stream(values()).filter(s -> s.code.equals(code)).findFirst();
    }
}
