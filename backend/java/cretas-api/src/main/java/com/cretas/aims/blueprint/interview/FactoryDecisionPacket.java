package com.cretas.aims.blueprint.interview;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * One server-owned decision in the guided factory-design conversation.
 *
 * <p>This is intentionally separate from chat prose. The Web client can render the question,
 * rationale, examples, recommendation boundary, unresolved state, and downstream impact without
 * parsing an LLM sentence.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactoryDecisionPacket {

    public static final String UNKNOWN_ANSWER_VALUE = "__FACTORY_DECISION_UNKNOWN__";

    private String decisionId;
    private String field;
    private String label;
    private String question;
    private String whyItMatters;
    private String inputType;
    private List<String> options;
    private boolean multiSelect;
    private boolean allowNone;
    @Builder.Default
    private boolean allowUnknown = true;
    @Builder.Default
    private String unknownAnswerValue = UNKNOWN_ANSWER_VALUE;
    private String state;
    private String acceptedDecision;
    private String unresolvedFollowUp;
    private String recommendation;
    private String recommendationReason;
    @Builder.Default
    private List<GuidanceChoice> guidanceChoices = new ArrayList<>();
    @Builder.Default
    private List<String> downstreamImpacts = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GuidanceChoice {
        private String label;
        /** Optional exact answer. Null means this is an editable example, not a silent default. */
        private String value;
        private String example;
        private String consequence;
    }
}
