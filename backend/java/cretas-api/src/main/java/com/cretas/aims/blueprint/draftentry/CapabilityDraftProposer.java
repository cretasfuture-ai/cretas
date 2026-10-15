package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.CapabilityView;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Read-only: builds one capability's DRAFT request from the factory's current blueprint, so a client
 * does not assemble 24 request shapes itself. It never writes and never calls the command.
 *
 * <p>The rule that matters: <b>a field the blueprint has no fact for is not filled with a
 * plausible value.</b> It is left empty and listed in {@link Proposed#requiredInputs()} with the
 * reason and the constraint the command will enforce. Command rules that follow from facts the
 * proposer did fill (a food-handling position needs a health certificate) may be pre-filled.</p>
 *
 * <p>{@link BlueprintDraftProposalController} has already checked the blueprint is the factory's
 * current DRAFT/VALIDATED one and that this capability is included and READY in it.</p>
 */
public interface CapabilityDraftProposer {

    /** Must have an open {@link CapabilityDraftEntry}; the proposal is posted to its preview. */
    String capabilityId();

    /**
     * @return one item per DRAFT request the capability needs (accounts: one; products: one per
     *         product). Each request is typed as the command's own request record, so field names
     *         cannot drift from it; fields listed in an item's {@code requiredInputs} are emitted as
     *         JSON null.
     */
    Proposed propose(Source source);

    record Source(String factoryId, BlueprintResponse blueprint, CapabilityView capability, String sourceDigest) {
    }

    /**
     * How the client renders a required input. Machine-readable so the client never has to guess a
     * widget from the wording of {@link RequiredInput#constraint()}.
     */
    enum InputType { TEXT, INTEGER, DECIMAL, BOOLEAN, ENUM, MULTI_ENUM, DATE, STRUCTURE }

    /**
     * The shape of a {@link InputType#STRUCTURE} input, so the client renders a typed form instead of a
     * JSON box.
     * <ul>
     *   <li>{@code LIST}: a JSON array of objects; every row has the keys of {@link RequiredInput#itemFields()}.</li>
     *   <li>{@code OBJECT}: one JSON object with the keys of {@link RequiredInput#itemFields()}.</li>
     *   <li>{@code VALUES}: a JSON array of plain values (a {@code List<String>} in the command, such as a
     *       form field's option codes). {@code itemFields} holds exactly one entry describing each value;
     *       its path is the list's own record component name. A list of plain values has no keys, so
     *       {@code LIST} cannot express it.</li>
     * </ul>
     */
    enum StructureKind { LIST, OBJECT, VALUES }

    /**
     * @param value what goes into the request verbatim (an enum name, a module id, a segment code) —
     *              always a value the command accepts, never a display name
     * @param label what the user sees
     */
    record Option(String value, String label) {

        /** Every constant of the command's enum, labelled from {@code labels} (the constant's name when absent). */
        public static <E extends Enum<E>> List<Option> ofEnum(Class<E> type, Map<E, String> labels) {
            return Arrays.stream(type.getEnumConstants())
                    .map(constant -> new Option(constant.name(), labels.getOrDefault(constant, constant.name())))
                    .toList();
        }
    }

    /**
     * One field the blueprint cannot answer. Built through the typed factories below so every input
     * states its type explicitly; there is deliberately no untyped constructor besides the canonical one.
     *
     * @param path       JSON path inside the request, e.g. {@code positions[0].minimumHeadcount}
     * @param label      what to ask the user, in their words
     * @param reason     why the blueprint cannot answer it
     * @param constraint what the command will accept, for people; must agree with the typed fields
     * @param inputType  how to render it
     * @param options    ENUM / MULTI_ENUM only: the values the command accepts
     * @param nullable   the user may explicitly leave it empty (the command accepts null)
     * @param min        INTEGER / DECIMAL: inclusive lower bound; MULTI_ENUM: minimum number of choices
     * @param max        INTEGER / DECIMAL: inclusive upper bound; MULTI_ENUM: maximum number of choices
     * @param maxLength  TEXT: maximum length the command accepts
     * @param structureKind STRUCTURE only: how the block is shaped; null for every other type
     * @param itemFields STRUCTURE only (non-empty): the block's fields, each with a <b>relative</b>
     *                   one-segment path that is a component name of the command's own record (a row of
     *                   a LIST, the OBJECT itself). They follow the same rules as top-level inputs and may
     *                   themselves be STRUCTUREs. Empty for every other type.
     * @param minItems   LIST / VALUES: the command's lower bound on rows (0 when it has none)
     * @param maxItems   LIST / VALUES: the command's upper bound on rows (null when it has none)
     * @param itemLabel  LIST / VALUES: what one row is called (「岗位」 → 「岗位 1」「岗位 2」)
     */
    record RequiredInput(String path, String label, String reason, String constraint,
                         InputType inputType, List<Option> options, boolean nullable,
                         BigDecimal min, BigDecimal max, Integer maxLength,
                         StructureKind structureKind, List<RequiredInput> itemFields,
                         Integer minItems, Integer maxItems, String itemLabel) {
        public RequiredInput {
            options = options == null ? List.of() : List.copyOf(options);
            itemFields = itemFields == null ? List.of() : List.copyOf(itemFields);
        }

        private static RequiredInput scalar(String path, String label, String reason, String constraint, InputType type,
                                            List<Option> options, BigDecimal min, BigDecimal max, Integer maxLength) {
            return new RequiredInput(path, label, reason, constraint, type, options, false, min, max, maxLength,
                    null, List.of(), null, null, null);
        }

        public static RequiredInput text(String path, String label, String reason, String constraint, Integer maxLength) {
            return scalar(path, label, reason, constraint, InputType.TEXT, List.of(), null, null, maxLength);
        }

        public static RequiredInput integer(String path, String label, String reason, String constraint,
                                            Integer min, Integer max) {
            return scalar(path, label, reason, constraint, InputType.INTEGER, List.of(),
                    min == null ? null : BigDecimal.valueOf(min), max == null ? null : BigDecimal.valueOf(max), null);
        }

        public static RequiredInput decimal(String path, String label, String reason, String constraint,
                                            BigDecimal min, BigDecimal max) {
            return scalar(path, label, reason, constraint, InputType.DECIMAL, List.of(), min, max, null);
        }

        public static RequiredInput bool(String path, String label, String reason, String constraint) {
            return scalar(path, label, reason, constraint, InputType.BOOLEAN, List.of(), null, null, null);
        }

        public static RequiredInput oneOf(String path, String label, String reason, String constraint, List<Option> options) {
            return scalar(path, label, reason, constraint, InputType.ENUM, options, null, null, null);
        }

        public static RequiredInput manyOf(String path, String label, String reason, String constraint, List<Option> options,
                                           int minCount, int maxCount) {
            return scalar(path, label, reason, constraint, InputType.MULTI_ENUM, options,
                    BigDecimal.valueOf(minCount), BigDecimal.valueOf(maxCount), null);
        }

        public static RequiredInput date(String path, String label, String reason, String constraint) {
            return scalar(path, label, reason, constraint, InputType.DATE, List.of(), null, null, null);
        }

        /**
         * A list of rows (positions, policies, forms …), each row typed by {@code itemFields}.
         *
         * @param minItems the command's lower bound on rows, 0 when it has none
         * @param maxItems the command's upper bound on rows, null when it has none
         */
        public static RequiredInput list(String path, String label, String reason, String constraint, String itemLabel,
                                         int minItems, Integer maxItems, List<RequiredInput> itemFields) {
            return new RequiredInput(path, label, reason, constraint, InputType.STRUCTURE, List.of(), false, null, null, null,
                    StructureKind.LIST, itemFields, minItems, maxItems, itemLabel);
        }

        /** One object of coupled fields (a decision tree's answers, an interval with its unit). */
        public static RequiredInput object(String path, String label, String reason, String constraint,
                                           List<RequiredInput> itemFields) {
            return new RequiredInput(path, label, reason, constraint, InputType.STRUCTURE, List.of(), false, null, null, null,
                    StructureKind.OBJECT, itemFields, null, null, null);
        }

        /** A list of plain values; {@code value} describes one value and its path is the list's component name. */
        public static RequiredInput values(String path, String label, String reason, String constraint, String itemLabel,
                                           int minItems, Integer maxItems, RequiredInput value) {
            return new RequiredInput(path, label, reason, constraint, InputType.STRUCTURE, List.of(), false, null, null, null,
                    StructureKind.VALUES, List.of(value), minItems, maxItems, itemLabel);
        }

        /** The same input, but the user may explicitly leave it empty. */
        public RequiredInput allowNull() {
            return new RequiredInput(path, label, reason, constraint, inputType, options, true, min, max, maxLength,
                    structureKind, itemFields, minItems, maxItems, itemLabel);
        }
    }

    /** One blueprint value the proposal was derived from. */
    record Evidence(String path, String value) {
    }

    /**
     * @param itemKey stable within the proposal (e.g. the product code), used by the client to keep
     *                the user's inputs attached to the right request
     */
    record ProposedItem(String itemKey, String label, Object request, List<RequiredInput> requiredInputs,
                        List<Evidence> evidence) {
        public ProposedItem {
            requiredInputs = List.copyOf(requiredInputs);
            evidence = List.copyOf(evidence);
        }
    }

    /** Why no request can be proposed yet, and what the user does next. */
    record Blocker(String code, String message, String nextStep) {
    }

    /**
     * Either items or blockers. Blockers are for what the proposer itself finds missing — typically
     * an upstream DRAFT this capability's request must pin (alerts pin a modules DRAFT) that has not
     * been written yet. Never invent the pin.
     */
    record Proposed(List<ProposedItem> items, List<Blocker> blockers) {
        public Proposed {
            items = List.copyOf(items);
            blockers = List.copyOf(blockers);
            if (!items.isEmpty() && !blockers.isEmpty()) {
                throw new IllegalArgumentException("a proposal has items or blockers, not both");
            }
        }

        public Proposed(List<ProposedItem> items) {
            this(items, List.of());
        }

        public static Proposed single(String itemKey, String label, Object request, List<RequiredInput> requiredInputs,
                                      List<Evidence> evidence) {
            return new Proposed(List.of(new ProposedItem(itemKey, label, request, requiredInputs, evidence)));
        }

        public static Proposed blocked(String code, String message, String nextStep) {
            return new Proposed(List.of(), List.of(new Blocker(code, message, nextStep)));
        }
    }
}
