package com.cretas.aims.service.consent;

import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.learning.FactoryNameDirectoryRepository;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 训练样本过滤规则 (Steve 2026-10-29 选 A) 的 Java 侧: 与 Python 跑同一份
 * {@code backend/python/smartbi/services/process_secret_filter_rule_cases.json}, 两边对同一输入必须给出同一结论
 * (Python 侧: {@code tests/test_process_secret_filter_rules_shared.py})。
 *
 * <ul>
 *   <li>term_cases: 只认全名 / 编码 / ID / 登记的专属别名; 纯数字名、品类词不收; 编码按边界匹配
 *       (F006 的「398」不误中批次号);</li>
 *   <li>row_cases: 走真实的 {@link ProcessSecretSampleFilter#checkTrainingSample} —— 只比本厂名单,
 *       没有工厂的样本不拿所有工厂的并集比;</li>
 *   <li>directory_cases: 本厂人员 / 客户精确匹配 (旧正则误拦的 14 句人名句 + 5 句客户句放行);</li>
 *   <li>content_cases: 工艺词单独出现不拦, 工艺词 + 具体数值拦。</li>
 * </ul>
 */
class ProcessSecretFilterRulesSharedContractTest {

    static final Path CASES = Path.of("..", "..", "python", "smartbi", "services",
            "process_secret_filter_rule_cases.json");

    static JsonNode cases;

    @BeforeAll
    static void load() throws Exception {
        assertThat(CASES).as("shared case file must exist (no silent skip)").exists();
        cases = new ObjectMapper().readTree(Files.readString(CASES, StandardCharsets.UTF_8));
    }

    static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    static List<ProcessSecretTerms.Product> products(JsonNode array) {
        List<ProcessSecretTerms.Product> out = new ArrayList<>();
        for (JsonNode p : array) {
            out.add(new ProcessSecretTerms.Product(text(p, "id"), text(p, "code"), text(p, "name"),
                    text(p, "category"), text(p, "product_category")));
        }
        return out;
    }

    static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.isNull() ? null : n.asText()));
        return out;
    }

    static List<String> mismatches(JsonNode checks, Predicate<String> matches) {
        List<String> bad = new ArrayList<>();
        for (JsonNode c : checks) {
            if (matches.test(c.get("text").asText()) != c.get("match").asBoolean()) {
                bad.add(c.get("text").asText());
            }
        }
        return bad;
    }

    static final Path WORDS = Path.of("..", "..", "python", "common", "training_rule_words.json");

    /**
     * Second review: a jar without the word file must fail closed with a RUNTIME exception (the filter's
     * {@code catch (RuntimeException)} then holds / drops the sample), never an Error from class initialisation.
     */
    @Test
    void aMissingWordFileFailsClosedWithARuntimeException() {
        assertThat(TrainingValueRules.loadWords("process-secret/no_such_words.json")).isNull();     // no throw
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TrainingValueRules.requireWords(null))
                .isInstanceOf(IllegalStateException.class);
        TrainingValueRules.requireWords();                                         // the real jar has it
        assertThat(TrainingValueRules.WORDS).isNotNull();
    }

    /** Review round 3, item 2: the word-file switch is a strict JSON boolean (a string "false" must not pass). */
    @Test
    void wordFileSwitchIsAStrictJsonBoolean() throws Exception {
        ObjectMapper om = new ObjectMapper();
        assertThat(ProcessSecretTerms.strictBoolean(om.readTree("{\"k\":true}"), "k")).isTrue();
        assertThat(ProcessSecretTerms.strictBoolean(om.readTree("{\"k\":false}"), "k")).isFalse();
        for (String bad : List.of("{\"k\":\"false\"}", "{\"k\":\"true\"}", "{\"k\":0}", "{\"k\":1}",
                "{\"k\":null}", "{}")) {
            assertThat(ProcessSecretTerms.strictBoolean(om.readTree(bad), "k")).as(bad).isNull();
        }
        assertThat(ProcessSecretTerms.fullNameCategoryWordMatches()).isFalse();      // Steve 2026-11-03 chose B
    }

    /** Q4: the word lists exist once; the jar carries a byte-identical copy of the Python file (pom copy-resources). */
    @Test
    void wordListsComeFromTheOneSharedFile() throws Exception {
        JsonNode source = new ObjectMapper().readTree(Files.readString(WORDS, StandardCharsets.UTF_8));
        assertThat(TrainingValueRules.WORDS).isEqualTo(source);
        assertThat(ProcessSecretTerms.CATEGORY_WORDS).containsExactlyElementsOf(strings(source.get("category_words")));
        assertThat(ProcessSecretTerms.CATEGORY_WORDS).contains("牛腱", "鸭脖", "米饭");
        try (var in = getClass().getClassLoader().getResourceAsStream(TrainingValueRules.WORDS_RESOURCE)) {
            assertThat(in).as("classpath copy").isNotNull();
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo(Files.readString(WORDS, StandardCharsets.UTF_8));
        }
    }

    @Test
    void maskCases() {
        List<String> bad = new ArrayList<>();
        for (JsonNode c : cases.get("mask_cases")) {
            String got = TrainingSampleRules.maskNumbers(c.get("text").asText());
            if (!got.equals(c.get("masked").asText())) {
                bad.add(c.get("text").asText() + " -> " + got);
            }
        }
        assertThat(bad).isEmpty();
    }

    @Test
    void termCases() {
        int total = 0;
        Set<Boolean> outcomes = new HashSet<>();
        for (JsonNode c : cases.get("term_cases")) {
            Set<String> terms = ProcessSecretTerms.secretTerms(products(c.get("products")), strings(c.get("aliases")));
            assertThat(mismatches(c.get("checks"), t -> ProcessSecretTerms.mentionsAny(terms, t)))
                    .as(c.get("name").asText() + " terms=" + terms).isEmpty();
            c.get("checks").forEach(k -> outcomes.add(k.get("match").asBoolean()));
            total += c.get("checks").size();
        }
        assertThat(total).isGreaterThanOrEqualTo(25);
        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
    }

    @Test
    void f006ProductNamed398IsNotCollectedButItsCodeIs() {
        JsonNode c = cases.get("term_cases").get(0);
        Set<String> terms = ProcessSecretTerms.secretTerms(products(c.get("products")), List.of());
        assertThat(terms).doesNotContain("398").contains("398礼盒", "ptshhpskgylyxgs0125");
    }

    @Test
    void rowCasesGoThroughTheRealFilterAndNeverUseEveryFactorysLists() {
        JsonNode rows = cases.get("row_cases");
        ProcessSecretAccessService access = mock(ProcessSecretAccessService.class);
        ProductTypeRepository repo = mock(ProductTypeRepository.class);
        FactoryNameDirectoryRepository directory = mock(FactoryNameDirectoryRepository.class);
        Map<String, JsonNode> byFactory = new java.util.LinkedHashMap<>();
        rows.get("products_by_factory").fields().forEachRemaining(e -> byFactory.put(e.getKey(), e.getValue()));
        // the loader's raw rows exactly as the native queries return them (product columns; the policy's aliases JSON)
        for (Map.Entry<String, JsonNode> e : byFactory.entrySet()) {
            Set<String> ids = new HashSet<>();
            List<Object[]> rowsOut = new ArrayList<>();
            List<String> aliases = new ArrayList<>();
            for (JsonNode p : e.getValue()) {
                ids.add(text(p, "id"));
                rowsOut.add(new Object[]{text(p, "id"), text(p, "code"), text(p, "name"), text(p, "category"),
                        text(p, "product_category")});
                if (text(p, "aliases") != null) {
                    aliases.add(text(p, "aliases"));
                }
            }
            when(access.protectedProductTypeIds(e.getKey())).thenReturn(ids);
            when(directory.findProtectedProductRows(org.mockito.ArgumentMatchers.eq(e.getKey()), any())).thenReturn(rowsOut);
            when(directory.findPolicyAliases(e.getKey())).thenReturn(aliases);
        }
        when(access.factoriesWithProtectedProducts()).thenReturn(Set.copyOf(byFactory.keySet()));
        ProcessSecretSampleFilter filter = new ProcessSecretSampleFilter(access, repo, directory);

        List<String> bad = new ArrayList<>();
        for (JsonNode c : rows.get("checks")) {
            String factory = text(c, "factory");
            ProcessSecretSampleFilter.SampleVerdict v = filter.checkTrainingSample("intent", factory, c.get("text").asText());
            boolean matched = v == ProcessSecretSampleFilter.SampleVerdict.PROTECTED;
            if (matched != c.get("match").asBoolean()) {
                bad.add(factory + ": " + c.get("text").asText() + " -> " + v);
            }
            if (factory == null || factory.isBlank()) {
                // no factory: nothing to match against -> not storable, and NOT "protected by someone's list"
                assertThat(v).isEqualTo(ProcessSecretSampleFilter.SampleVerdict.UNVERIFIED);
            }
        }
        assertThat(bad).isEmpty();
        // the union WOULD hit: the no-factory case is not vacuous
        assertThat(ProcessSecretTerms.mentionsAny(filter.allFactoriesTrainingTerms(), "398礼盒今天发了多少")).isTrue();
    }

    @Test
    void directoryCasesThroughTheRealFilter() {
        for (JsonNode c : cases.get("directory_cases")) {
            ProcessSecretAccessService access = mock(ProcessSecretAccessService.class);
            FactoryNameDirectoryRepository directory = mock(FactoryNameDirectoryRepository.class);
            when(access.protectedProductTypeIds("F1")).thenReturn(Set.of());
            when(directory.findPeopleNames("F1")).thenReturn(strings(c.get("people")));
            when(directory.findCustomerNames("F1")).thenReturn(strings(c.get("customers")));
            ProcessSecretSampleFilter filter = new ProcessSecretSampleFilter(access, mock(ProductTypeRepository.class), directory);
            assertThat(mismatches(c.get("checks"), t -> filter.checkTrainingSample("intent", "F1", t)
                    == ProcessSecretSampleFilter.SampleVerdict.PROTECTED)).as(c.get("name").asText()).isEmpty();
        }
    }

    @Test
    void the19SentencesTheOldRegexBlockedNowPass() {
        JsonNode c = cases.get("directory_cases").get(0);
        List<String> allowed = new ArrayList<>();
        c.get("checks").forEach(k -> {
            if (!k.get("match").asBoolean()) {
                allowed.add(k.get("text").asText());
            }
        });
        // positive control: the old proxy (still used by the egress redactor) really fires on them
        List<String> old = allowed.stream().filter(t -> TrainingSampleRules.PERSON.matcher(t).find()
                || (t.contains("客户") && TrainingSampleRules.ORG_NAME.matcher(t).find())).toList();
        assertThat(old).hasSizeGreaterThanOrEqualTo(19);
        assertThat(old).allSatisfy(t -> assertThat(TrainingSampleRules.textToStore(t, true)).isEqualTo(t));
    }

    @Test
    void contentCases() {
        List<String> bad = new ArrayList<>();
        Set<Boolean> outcomes = new HashSet<>();
        for (JsonNode c : cases.get("content_cases")) {
            boolean expected = c.get("blocked").asBoolean();
            outcomes.add(expected);
            if (TrainingSampleRules.utteranceHasBlockedContent(c.get("text").asText()) != expected) {
                bad.add(c.get("text").asText());
            }
        }
        assertThat(bad).isEmpty();
        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
    }
}
