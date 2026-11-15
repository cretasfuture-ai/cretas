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
 * canonical. The resource is not wired into recognition yet (PR-4), so this gate only guards the
 * data. The checker is also run against a deliberately broken copy so a green result proves the
 * instrument can go red.</p>
 */
class IntentDomainTableContractTest {

    private static final int MAX_DOMAINS = 14;

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
        }
        for (String code : canonicalCodes) {
            if (!seen.contains(code)) out.add("canonical intent missing from table: " + code);
        }
        for (String domain : domains) {
            if (!usedDomains.contains(domain)) out.add("declared domain never used: " + domain);
        }
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
    void checkerGoesRedWhenAnIntentLosesItsDomainOrIsMissing() {
        Set<String> canonical = FactoryIntentCatalog.canonicalCodes();

        JsonNode noDomain = readTable().deepCopy();
        ((ObjectNode) noDomain.path("intents").get(0)).remove("domain");
        assertThat(problems(noDomain, canonical)).anyMatch(p -> p.startsWith("no domain: "));

        JsonNode noAction = readTable().deepCopy();
        ((ObjectNode) noAction.path("intents").get(1)).put("action", "");
        assertThat(problems(noAction, canonical)).anyMatch(p -> p.startsWith("no action: "));

        JsonNode missingRow = readTable().deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) missingRow.path("intents")).remove(2);
        assertThat(problems(missingRow, canonical)).anyMatch(p -> p.startsWith("canonical intent missing from table: "));
    }
}
