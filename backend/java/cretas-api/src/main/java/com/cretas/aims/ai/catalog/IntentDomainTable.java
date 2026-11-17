package com.cretas.aims.ai.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 意图业务域表 {@code ai/intent-domain-table.json} 的只读视图 (意图蒸馏第③步 PR-4)。
 *
 * <p>识别时按业务域缩小候选发生在 Python 决策层 ({@code backend/python/ai/intent_domain.py}, 读同一张表的同步副本)。
 * Java 侧只做一件事: 把本表的 {@code catalog_version} 随识别请求下发, Python 版本相同才收窄 —— Java 与 Python
 * 分开发布, 版本对不上时 Python 不收窄 (宁可放宽)。表读不了时返回空版本, 等同老客户端, 识别照常。</p>
 *
 * <p>⛔ 业务域不授权任何东西: 权限、写确认、审批全按现有链路走; 这里不提供任何可以被当成闸用的判断。</p>
 */
@Slf4j
public final class IntentDomainTable {

    static final String RESOURCE = "/ai/intent-domain-table.json";

    private static final Loaded TABLE = load();

    private IntentDomainTable() {}

    record Loaded(String catalogVersion, Map<String, String> primary, Map<String, String> secondary) {}

    static Loaded parse(JsonNode doc) {
        if (doc == null || doc.path("schema_version").asInt() != 1) {
            throw new IllegalStateException("intent domain table schema_version must be 1");
        }
        String version = doc.path("catalog_version").asText("").trim();
        if (version.isEmpty()) throw new IllegalStateException("intent domain table has no catalog_version");
        Map<String, String> primary = new LinkedHashMap<>();
        Map<String, String> secondary = new LinkedHashMap<>();
        for (JsonNode row : doc.path("intents")) {
            String code = row.path("intent_code").asText("");
            primary.put(code, row.path("domain").asText(""));
            if (row.hasNonNull("secondary_domain")) secondary.put(code, row.path("secondary_domain").asText());
        }
        return new Loaded(version, Map.copyOf(primary), Map.copyOf(secondary));
    }

    private static Loaded load() {
        try (InputStream in = IntentDomainTable.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException(RESOURCE + " missing");
            return parse(new ObjectMapper().readTree(in));
        } catch (Exception e) {
            // 读不了 ⇒ 不下发版本 ⇒ Python 不收窄; 识别不因为业务域失败 (留一条告警)
            log.warn("意图业务域表读取失败, 识别不按业务域缩小候选: {}", e.getMessage());
            return null;
        }
    }

    /** 下发给 Python 的目录版本; 表读不了时为空 (Python 视同老客户端, 不收窄)。 */
    public static Optional<String> catalogVersion() {
        return TABLE == null ? Optional.empty() : Optional.of(TABLE.catalogVersion());
    }

    /** 主业务域; 表外意图 (工厂自建等) 为空。 */
    public static Optional<String> domainOf(String intentCode) {
        return TABLE == null || intentCode == null ? Optional.empty() : Optional.ofNullable(TABLE.primary().get(intentCode));
    }

    /** 次业务域 (只有域存疑的意图才有)。 */
    public static Optional<String> secondaryDomainOf(String intentCode) {
        return TABLE == null || intentCode == null ? Optional.empty() : Optional.ofNullable(TABLE.secondary().get(intentCode));
    }
}
