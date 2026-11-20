package com.cretas.aims.ai.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage gate for ai/intent-domain-table.json (INTENT step 3 / PR-4 data slice).
 *
 * <p>Every canonical intent in ai/factory-intent-catalog.json must have exactly one row with a
 * declared business domain and a declared action type; no row may point at an intent that is not
 * canonical. PR-4 wires it into recognition (Python ai/intent_domain.py narrows the decision-layer
 * candidates; behaviour gates in backend/python/tests/test_intent_domain_scope_contract.py), so this
 * gate also guards secondary_domain and the per-domain keywords. The checker is also run against a deliberately broken copy so a green result proves the
 * instrument can go red.</p>
 */
class IntentDomainTableContractTest {

    private static final int MAX_DOMAINS = 14;
    /** 跨域通用入口: 识别时不因为「域已确定」被排除 (表第 3 节), Python ai/intent_domain.py ALWAYS_CANDIDATE_DOMAINS。 */
    private static final Set<String> CROSS_DOMAIN = Set.of("SYSTEM", "ANALYTICS");

    private static JsonNode readTable() {
        try (InputStream in = IntentDomainTableContractTest.class.getResourceAsStream("/ai/intent-domain-table.json")) {
            assertThat(in).as("/ai/intent-domain-table.json on classpath").isNotNull();
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Returns every violation found (never stops at the first). Empty list means the table is complete. */
    static List<String> problems(JsonNode table, Set<String> canonicalCodes) {
        List<String> out = new ArrayList<>();
        if (table.path("catalog_version").asText("").isBlank()) out.add("catalog_version missing");

        Set<String> domains = codes(table.path("domains"));
        Set<String> actions = codes(table.path("actions"));
        if (domains.isEmpty() || domains.size() > MAX_DOMAINS) out.add("domain count out of range: " + domains.size());
        if (!actions.containsAll(Set.of("QUERY", "CREATE", "UPDATE", "DELETE", "APPROVE", "ANALYZE"))) {
            out.add("action enum incomplete: " + actions);
        }

        Set<String> seen = new HashSet<>();
        Set<String> usedDomains = new HashSet<>();
        for (JsonNode row : table.path("intents")) {
            String code = row.path("intent_code").asText("");
            if (code.isBlank()) { out.add("row without intent_code"); continue; }
            if (!seen.add(code)) out.add("duplicate row: " + code);
            if (!canonicalCodes.contains(code)) out.add("row for non-canonical intent: " + code);
            String domain = row.path("domain").asText("");
            String action = row.path("action").asText("");
            if (domain.isBlank()) out.add("no domain: " + code);
            else if (!domains.contains(domain)) out.add("undeclared domain " + domain + ": " + code);
            else usedDomains.add(domain);
            if (action.isBlank()) out.add("no action: " + code);
            else if (!actions.contains(action)) out.add("undeclared action " + action + ": " + code);
            if ("manual".equals(row.path("domain_basis").asText()) || "manual".equals(row.path("action_basis").asText())) {
                if (row.path("reason").asText("").isBlank()) out.add("manual label without reason: " + code);
            }
            if (row.path("uncertain").asBoolean(false) && row.path("uncertain_note").asText("").isBlank()) {
                out.add("uncertain row without note: " + code);
            }
            if (row.has("secondary_domain")) {
                String secondary = row.path("secondary_domain").asText("");
                if (!domains.contains(secondary)) out.add("undeclared secondary_domain " + secondary + ": " + code);
                if (secondary.equals(domain)) out.add("secondary_domain equals primary: " + code);
                if (!row.path("uncertain").asBoolean(false)) out.add("secondary_domain on a row that is not uncertain: " + code);
            }
        }
        for (String code : canonicalCodes) {
            if (!seen.contains(code)) out.add("canonical intent missing from table: " + code);
        }
        for (String domain : domains) {
            if (!usedDomains.contains(domain)) out.add("declared domain never used: " + domain);
        }
        // 业务词: SYSTEM / ANALYTICS 是跨域通用入口, 不设词 (设了就会被当成一个可以收窄到的域);
        // 其它每个业务域都要有词, 否则那个域永远判不出来
        for (JsonNode d : table.path("domains")) {
            String code = d.path("code").asText("");
            boolean crossDomain = CROSS_DOMAIN.contains(code);
            int words = d.path("keywords").size();
            if (crossDomain && words > 0) out.add("cross-domain entry must have no keywords: " + code);
            if (!crossDomain && words == 0) out.add("business domain without keywords: " + code);
        }
        if (!domains.containsAll(CROSS_DOMAIN)) out.add("cross-domain entries not declared: " + CROSS_DOMAIN);
        return out;
    }

    private static Set<String> codes(JsonNode array) {
        Set<String> out = new LinkedHashSet<>();
        array.forEach(n -> out.add(n.path("code").asText("")));
        out.remove("");
        return out;
    }

    @Test
    void everyCanonicalIntentHasABusinessDomainAndActionType() {
        JsonNode table = readTable();
        Set<String> canonical = FactoryIntentCatalog.canonicalCodes();
        assertThat(canonical).as("instrument sanity: catalog must be non-empty").isNotEmpty();
        assertThat(table.path("intents")).as("table rows").hasSize(canonical.size());
        assertThat(problems(table, canonical)).isEmpty();
    }

    @Test
    void secondaryDomainsComeFromTheUncertainListAndJavaSendsTheTableVersion() {
        JsonNode table = readTable();
        int secondary = 0;
        int uncertain = 0;
        for (JsonNode row : table.path("intents")) {
            if (row.path("uncertain").asBoolean(false)) uncertain++;
            if (row.has("secondary_domain")) {
                secondary++;
                assertThat(IntentDomainTable.secondaryDomainOf(row.path("intent_code").asText()))
                        .contains(row.path("secondary_domain").asText());
            }
        }
        // 第 5 节 52 条存疑里, 存疑点写明了另一个候选域的 35 条有次域; 其余 17 条只关于动作, 没有次域
        assertThat(uncertain).isEqualTo(52);
        assertThat(secondary).isEqualTo(35);
        assertThat(IntentDomainTable.catalogVersion()).contains(table.path("catalog_version").asText());
        assertThat(IntentDomainTable.domainOf("COLD_CHAIN_TEMP_QUERY")).contains("EQUIPMENT");
        assertThat(IntentDomainTable.secondaryDomainOf("COLD_CHAIN_TEMP_QUERY")).contains("QUALITY");
        assertThat(IntentDomainTable.domainOf("F006_CUSTOM_NOT_IN_TABLE")).isEmpty();
    }

    @Test
    void checkerGoesRedWhenAnIntentLosesItsDomainOrIsMissing() {
        Set<String> canonical = FactoryIntentCatalog.canonicalCodes();

        JsonNode noDomain = readTable().deepCopy();
        ((ObjectNode) noDomain.path("intents").get(0)).remove("domain");
        assertThat(problems(noDomain, canonical)).anyMatch(p -> p.startsWith("no domain: "));

        JsonNode noAction = readTable().deepCopy();
        ((ObjectNode) noAction.path("intents").get(1)).put("action", "");
        assertThat(problems(noAction, canonical)).anyMatch(p -> p.startsWith("no action: "));

        JsonNode badSecondary = readTable().deepCopy();
        ObjectNode row3 = (ObjectNode) badSecondary.path("intents").get(3);
        row3.put("secondary_domain", row3.path("domain").asText());
        assertThat(problems(badSecondary, canonical)).anyMatch(p -> p.startsWith("secondary_domain equals primary: "));
        row3.put("secondary_domain", "NOT_A_DOMAIN");
        assertThat(problems(badSecondary, canonical)).anyMatch(p -> p.startsWith("undeclared secondary_domain "));

        JsonNode keywordOnSystem = readTable().deepCopy();
        for (JsonNode d : keywordOnSystem.path("domains")) {
            if ("SYSTEM".equals(d.path("code").asText())) ((ObjectNode) d).putArray("keywords").add("系统");
        }
        assertThat(problems(keywordOnSystem, canonical)).contains("cross-domain entry must have no keywords: SYSTEM");

        JsonNode missingRow = readTable().deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) missingRow.path("intents")).remove(2);
        assertThat(problems(missingRow, canonical)).anyMatch(p -> p.startsWith("canonical intent missing from table: "));
    }
}
