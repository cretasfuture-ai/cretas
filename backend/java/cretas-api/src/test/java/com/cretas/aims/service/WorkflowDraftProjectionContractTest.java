package com.cretas.aims.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowDraftProjectionContractTest {

    private static final String SOURCE = """
            {"rawMaterials":["合成原料"],"steps":[
              {"process":"清洗","outputs":[{"kind":"SEMI_FINISHED","name":"清洗料","unit":"kg"}]},
              {"process":"装盒","outputs":[{"kind":"FINISHED_GOOD","name":"合成成品","unit":"盒"}]}
            ]}
            """;
    private final WorkflowDraftProjection projection = new WorkflowDraftProjection(new ObjectMapper());

    @Test
    void originalCallerSemanticRequestStaysReviewableWithoutInventedFields() throws Exception {
        WorkflowDraftProjection.Result result = projection.project(SOURCE, SOURCE);

        assertEquals(WorkflowDraftProjection.Decision.REVIEWABLE, result.decision());
        assertEquals(Set.of("rawMaterials", "steps"), result.spec().keySet());
        assertEquals(List.of("合成原料"), result.spec().get("rawMaterials"));
        assertTrue(result.issues().isEmpty());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(SOURCE.getBytes(StandardCharsets.UTF_8))), result.sourceSha256());
    }

    @Test
    void jsonMemberOrderDoesNotChangeCallerMeaning() throws Exception {
        Map<String, Object> source = new ObjectMapper().readValue(SOURCE,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        String reordered = new ObjectMapper().writeValueAsString(Map.of(
                "steps", source.get("steps"), "rawMaterials", source.get("rawMaterials")));

        assertEquals(WorkflowDraftProjection.Decision.REVIEWABLE, projection.project(reordered, SOURCE).decision());
    }

    @Test
    void materialMentionedAsOutputCannotReplaceTheCallerRawMaterial() {
        String changed = SOURCE.replace("\"rawMaterials\":[\"合成原料\"]", "\"rawMaterials\":[\"清洗料\"]");

        WorkflowDraftProjection.Result result = projection.project(changed, SOURCE);

        assertEquals(WorkflowDraftProjection.Decision.REJECTED, result.decision());
        assertTrue(result.issues().contains("RAW_MATERIAL_SOURCE_CHANGED"));
        assertTrue(result.spec().isEmpty());
    }

    @Test
    void stepNamesOutputUnitsAndOrderMustRemainCallerOwned() {
        for (String changed : List.of(SOURCE.replace("装盒", "包装"), SOURCE.replace("盒", "箱"),
                SOURCE.replace("SEMI_FINISHED", "FINISHED_GOOD"))) {
            WorkflowDraftProjection.Result result = projection.project(changed, SOURCE);
            assertEquals(WorkflowDraftProjection.Decision.REJECTED, result.decision());
            assertTrue(result.issues().contains("STEP_SOURCE_CHANGED"));
        }
    }

    @Test
    void reversingTheCallerStepOrderIsRejected() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var changed = mapper.readTree(SOURCE);
        ArrayNode steps = (ArrayNode) changed.get("steps");
        var firstStep = steps.remove(0);
        steps.add(firstStep);

        WorkflowDraftProjection.Result result = projection.project(mapper.writeValueAsString(changed), SOURCE);

        assertEquals(WorkflowDraftProjection.Decision.REJECTED, result.decision());
        assertTrue(result.issues().contains("STEP_SOURCE_CHANGED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "合成原料清洗为清洗料，再装盒成合成成品", "null",
            "{\"nodes\":[{\"name\":\"合成原料\"}],\"edges\":[]}",
            "{\"rawMaterials\":[\"合成原料\"],\"steps\":[null]}"})
    void absentOrGraphOnlyCallerBindingIsNotQualified(String originalRequest) {
        WorkflowDraftProjection.Result result = projection.project(SOURCE, originalRequest);

        assertEquals(WorkflowDraftProjection.Decision.REVIEW_REQUIRED, result.decision());
        assertEquals(List.of("SOURCE_BINDING_REQUIRED"), result.issues());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"steps\":[{\"processName\":\"清洗\",\"outputType\":\"SEMI_FINISHED\"}]}",
            "{\"rawMaterials\":[\"合成原料\"],\"steps\":[null]}",
            "{\"rawMaterials\":[\"合成原料\"],\"steps\":[{\"process\":\"清洗\"}]}",
            "{\"rawMaterials\":[\"合成原料\"],\"steps\":[],\"quantity\":8.4}",
            "{\"rawMaterials\":[\"合成原料\"],\"steps\":[],\"extraction\":{}}",
            "{\"rawMaterials\":[\"合成原料\"],\"steps\":[],\"calculation\":{}}",
            "{\"rawMaterials\":[\"合成原料\"],\"steps\":[],\"claimed_total\":\"8.4\"}",
            "{\"rawMaterials\":[\"合成原料\"],\"rawMaterials\":[\"清洗料\"],\"steps\":[{}]}",
            "{\"rawMaterials\":[\"合成原料\"],\"steps\":[{}],\"nodes\":[]}",
            "{\"rawMaterials\":[\"合成原料\"],\"steps\":[{\"process\":\"清洗\",\"injectionAmount\":NaN}]}",
            "{\"rawMaterials\":[\"合成原料\"],\"steps\":[{\"process\":\"清洗\",\"outputs\":[{\"kind\":\"FINISHED_GOOD\",\"name\":\"成品\",\"unit\":\"盒\",\"byproduct\":\"false\"}]}]}"
    })
    void illegalStructuresAreRejectedEvenWithoutSourceBinding(String response) {
        WorkflowDraftProjection.Result result = projection.project(response, null);

        assertEquals(WorkflowDraftProjection.Decision.REJECTED, result.decision());
        assertTrue(result.spec().isEmpty());
        assertTrue(!result.issues().isEmpty());
    }

    @Test
    void duplicateNestedKeysAndTrailingJsonAreRejected() {
        String duplicate = SOURCE.replace("\"process\":\"清洗\"", "\"process\":\"清洗\",\"process\":\"清洗\"");

        for (String invalid : List.of(duplicate, SOURCE + "{}", "```json\n" + SOURCE + "\n```")) {
            assertEquals(WorkflowDraftProjection.Decision.REJECTED, projection.project(invalid, SOURCE).decision());
        }
    }

    @Test
    void documentedMergeMultipleOutputsAndByproductFieldsArePreserved() {
        String source = """
                {"rawMaterials":["主料甲","主料乙"],"steps":[{"process":"合流",
                "inputs":["辅料丙"],"outputs":[
                {"kind":"FINISHED_GOOD","name":"主成品","unit":"盒"},
                {"kind":"SEMI_FINISHED","name":"副产物","unit":"kg","byproduct":true}]}]}
                """;

        WorkflowDraftProjection.Result result = projection.project(source, source);

        assertEquals(WorkflowDraftProjection.Decision.REVIEWABLE, result.decision());
        assertEquals(List.of("主料甲", "主料乙"), result.spec().get("rawMaterials"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "100"})
    void documentedCookSeasoningsAllowRatioBoundariesAndTinyPositiveDosage(String ratio) {
        String source = """
                {"rawMaterials":["原料"],"steps":[{"process":"卤制","processCategory":"熟制",
                "seasonings":[{"name":"盐","dosagePerKgG":0.000000000001,"subsequentPotRatio":%s}],
                "outputs":[{"kind":"FINISHED_GOOD","name":"熟成品","unit":"kg"}]}]}
                """.formatted(ratio);

        assertEquals(WorkflowDraftProjection.Decision.REVIEWABLE, projection.project(source, source).decision());
    }

    @Test
    void injectionAmountIsKeptOnlyForAnExplicitCallerInjectionStep() {
        String source = """
                {"rawMaterials":["原料"],"steps":[{"process":"注射","processCategory":"注射",
                "injectionAmount":0.25,"outputs":[{"kind":"FINISHED_GOOD","name":"成品","unit":"kg"}]}]}
                """;

        assertEquals(WorkflowDraftProjection.Decision.REVIEWABLE, projection.project(source, source).decision());
        for (String invalid : List.of(source.replace("\"processCategory\":\"注射\"", "\"processCategory\":\"切配\""),
                source.replace("0.25", "0"), source.replace("0.25", "\"null\""))) {
            assertEquals(WorkflowDraftProjection.Decision.REJECTED, projection.project(invalid, source).decision());
        }
    }

    @Test
    void equivalentNumericSpellingDoesNotInventAnAdditionalFact() {
        String source = """
                {"rawMaterials":["原料"],"steps":[{"process":"熟制","processCategory":"熟制",
                "seasonings":[{"name":"盐","dosagePerKgG":2,"subsequentPotRatio":100}],
                "outputs":[{"kind":"FINISHED_GOOD","name":"熟成品","unit":"kg"}]}]}
                """;

        assertEquals(WorkflowDraftProjection.Decision.REVIEWABLE,
                projection.project(source.replace(":2", ":2.0").replace(":100", ":100.0"), source).decision());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "101", "\"null\"", "null", "true"})
    void invalidCookRatioCannotBecomeAQualifiedDraft(String ratio) {
        String source = """
                {"rawMaterials":["原料"],"steps":[{"process":"熟制","processCategory":"熟制",
                "seasonings":[{"name":"盐","dosagePerKgG":2,"subsequentPotRatio":%s}],
                "outputs":[{"kind":"FINISHED_GOOD","name":"熟成品","unit":"kg"}]}]}
                """.formatted(ratio);

        assertEquals(WorkflowDraftProjection.Decision.REJECTED, projection.project(source, source).decision());
    }

    @Test
    void mapperDuplicateKeyPolicyIsNotChangedForOtherCallers() throws Exception {
        ObjectMapper callerMapper = new ObjectMapper();
        new WorkflowDraftProjection(callerMapper).project(SOURCE, SOURCE);

        assertEquals(2, callerMapper.readTree("{\"same\":1,\"same\":2}").get("same").intValue());
    }
}
