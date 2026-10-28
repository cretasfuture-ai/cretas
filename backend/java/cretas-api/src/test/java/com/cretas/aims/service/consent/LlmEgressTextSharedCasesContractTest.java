package com.cretas.aims.service.consent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 外发文本脱敏: Java 与 Python 同一套规则 (Steve 2026-10-27)。共用用例
 * backend/python/common/llm_egress_text_cases.json, Python 侧 tests/test_llm_egress_text_shared.py 跑同一份,
 * 两边输出都必须与 expected 一字不差 —— 改一边的规则不改另一边, 这里或 Python 那边会红。
 *
 * <p>{@code -Degress.cases.dump=<文件>}: 把 Java 的实际输出写成一份带 expected 的用例 (新增用例时用来定标), 不断言。
 */
class LlmEgressTextSharedCasesContractTest {

    static final Path SHARED_CASES = Path.of("..", "..", "python", "common", "llm_egress_text_cases.json");

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void javaProducesExactlyTheSharedExpectedOutputs() throws Exception {
        JsonNode root = mapper.readTree(Files.readString(SHARED_CASES, StandardCharsets.UTF_8));
        String dump = System.getProperty("egress.cases.dump");
        LlmEgressRedactor redactor = new LlmEgressRedactor(mapper);
        int checked = 0;
        for (JsonNode c : root.get("cases")) {
            List<String> texts = new ArrayList<>();
            c.get("texts").forEach(t -> texts.add(t.isNull() ? null : t.asText()));
            Map<String, LlmEgressRedactor.Party> parties = new LinkedHashMap<>();
            if (c.has("known_parties")) {
                c.get("known_parties").fields().forEachRemaining(
                        e -> parties.put(e.getKey(), LlmEgressRedactor.Party.valueOf(e.getValue().asText())));
            }
            List<String> names = new ArrayList<>();
            if (c.has("protected_names")) {
                c.get("protected_names").forEach(n -> names.add(n.asText()));
            }
            LlmEgressRedactor.Result r = redactor.redactTexts(texts, parties, names, c.get("detect_names").asBoolean());
            String name = c.get("name").asText();
            if (dump != null) {
                ObjectNode o = (ObjectNode) c;
                o.set("expected", r.refused() ? null : mapper.valueToTree(r.texts()));
                o.put("refusal", r.refusal());
                if (c.has("answers")) {
                    for (JsonNode a : c.get("answers")) {
                        ((ObjectNode) a).put("expected", r.restore(a.get("answer").asText()));
                    }
                }
                continue;
            }
            assertThat(c.has("expected")).as(name + ": 缺 expected").isTrue();
            assertThat(r.refusal()).as(name).isEqualTo(c.path("refusal").isNull() ? null : c.path("refusal").asText(null));
            List<String> expected = new ArrayList<>();
            if (!c.get("expected").isNull()) {
                c.get("expected").forEach(t -> expected.add(t.isNull() ? null : t.asText()));
            }
            assertThat(r.refused() ? List.of() : r.texts()).as(name).containsExactlyElementsOf(expected);
            for (JsonNode a : c.path("answers")) {
                assertThat(r.restore(a.get("answer").asText())).as(name + " / 还原").isEqualTo(a.get("expected").asText());
            }
            checked++;
        }
        if (dump != null) {
            Files.writeString(Path.of(dump), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                    StandardCharsets.UTF_8);
            return;
        }
        assertThat(checked).isEqualTo(root.get("cases").size()).isGreaterThanOrEqualTo(10);
        assertThat(root.get("cases").findValues("answers")).isNotEmpty();
        assertThat(((ArrayNode) root.get("cases")).size()).isPositive();
    }
}
