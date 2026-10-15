package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest;
import com.cretas.aims.entity.moduleconfig.ModuleConfigDraft;
import com.cretas.aims.repository.moduleconfig.ModuleConfigDraftItemRepository;
import com.cretas.aims.repository.moduleconfig.ModuleConfigDraftRepository;
import com.cretas.aims.service.formschemadraft.FormSchemaDraftCommand;
import com.cretas.aims.service.formschemadraft.FormSchemaDraftModels.DraftRequest;
import com.cretas.aims.service.formschemadraft.FormSchemaDraftModels.FieldInput;
import com.cretas.aims.service.formschemadraft.FormSchemaDraftModels.FieldType;
import com.cretas.aims.service.formschemadraft.FormSchemaDraftModels.FormInput;
import com.cretas.aims.service.formschemadraft.FormSchemaDraftModels.ModuleConfigPin;
import com.cretas.aims.service.formschemadraft.FormSchemaDraftModels.ResponsibleRole;
import com.cretas.aims.service.formschemadraft.FormSchemaDraftModels.RetentionClass;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code forms} proposal: one typed form per interview form template ({@code ApplyAIInitRequest.schemas}),
 * pinned to the latest same-lineage module-config DRAFT.
 *
 * <p>The interview's field shape is {@code {name, label, type: string|number|date|boolean|enum,
 * required, unit}}. Only the types that name exactly one of the command's field types are filled:
 * {@code string → TEXT}, {@code date → DATE}, {@code boolean → BOOLEAN}. A {@code number} could be an
 * integer, a decimal or a quantity with a unit, and an {@code enum} could be single or multi select
 * with options the interview never listed — those are asked, not picked.</p>
 *
 * <p>{@code suggestedData.codeSegments} (编码分段) is the material code's level-1 segmentation, not a
 * form; the form command has no slot for it, so it is listed in the evidence as "未带入".</p>
 *
 * <p>The interview never chose a module, retention class or responsible role for a form; those
 * are asked. Retention drives the command's audit and signature rules, so it is not defaulted.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class FormsDraftProposer implements CapabilityDraftProposer {

    /** Interview field type → the one command field type it can only mean. */
    private static final Map<String, FieldType> EXACT_TYPES = Map.of(
            "string", FieldType.TEXT, "date", FieldType.DATE, "boolean", FieldType.BOOLEAN);
    private static final String FIELD_KEY = "[a-z][a-z0-9_]{0,59}";
    /** The command's limits (title / label / key length, text length, option count, decimal scale). */
    static final int MAX_TITLE = 200;
    static final int MAX_KEY = 60;
    static final int MAX_TEXT_LENGTH = 2000;
    static final int MAX_SCALE = 6;
    static final int MAX_UNIT_CODE = 30;
    static final List<Option> RETENTION_OPTIONS = Option.ofEnum(RetentionClass.class, Map.of(
            RetentionClass.FOOD_SAFETY_CRITICAL, "食品安全关键记录（730 天）",
            RetentionClass.QUALITY_RECORD, "质量记录（365 天）",
            RetentionClass.OPERATIONAL_RECORD, "运行记录（180 天）",
            RetentionClass.TRANSIENT_WORKSHEET, "临时工作表（30 天）"));
    static final List<Option> RESPONSIBLE_OPTIONS = Option.ofEnum(ResponsibleRole.class, Map.of(
            ResponsibleRole.QUALITY_INSPECTOR, "质检员", ResponsibleRole.PRODUCTION_SUPERVISOR, "生产主管",
            ResponsibleRole.WAREHOUSE_SUPERVISOR, "仓库主管", ResponsibleRole.SANITATION_OPERATOR, "清洁消毒员"));
    private static final Map<FieldType, String> FIELD_TYPE_LABELS = Map.ofEntries(
            Map.entry(FieldType.TEXT, "文本"), Map.entry(FieldType.INTEGER, "整数"), Map.entry(FieldType.DECIMAL, "小数"),
            Map.entry(FieldType.QUANTITY, "带单位的数量"), Map.entry(FieldType.BOOLEAN, "是/否"),
            Map.entry(FieldType.DATE, "日期"), Map.entry(FieldType.DATETIME, "日期时间"),
            Map.entry(FieldType.SINGLE_SELECT, "单选"), Map.entry(FieldType.MULTI_SELECT, "多选"),
            Map.entry(FieldType.PHOTO, "照片"), Map.entry(FieldType.SIGNATURE, "签名"));
    static final List<Option> FIELD_TYPE_OPTIONS = Option.ofEnum(FieldType.class, FIELD_TYPE_LABELS);
    static final List<Option> NUMBER_TYPE_OPTIONS = fieldTypes(FieldType.INTEGER, FieldType.DECIMAL, FieldType.QUANTITY);
    static final List<Option> SELECT_TYPE_OPTIONS = fieldTypes(FieldType.SINGLE_SELECT, FieldType.MULTI_SELECT);
    /** The command's row limits ({@code MAX_FORMS}, {@code MAX_FIELDS}, {@code MAX_OPTIONS}, option code width) and lengths. */
    static final int MIN_FORMS = 1;
    static final int MAX_FORMS = 200;
    static final int MIN_FIELDS = 1;
    static final int MAX_FIELDS = 200;
    static final int MAX_OPTIONS = 50;
    static final int MAX_OPTION_CODE = 40;
    static final int MAX_OPTION_CODES_WIDTH = 2000;
    static final int MAX_HELP_TEXT = 500;
    static final int MAX_REASON = 500;
    private static final String FIELDS_CONSTRAINT = MIN_FIELDS + "–" + MAX_FIELDS + " 个字段，同一表单内字段标识不能重复；"
            + "FOOD_SAFETY_CRITICAL 记录必须同时有 SIGNATURE 和 DATETIME 字段";
    private static final String RETENTION_CONSTRAINT = "FOOD_SAFETY_CRITICAL（保存 730 天，必须有签名字段和记录时间字段）、"
            + "QUALITY_RECORD（365 天）、OPERATIONAL_RECORD（180 天）、TRANSIENT_WORKSHEET（30 天，不能有签名字段）之一";
    private static final String WHY_NO_FIELDS = "访谈的表单模板没有可用的字段";
    /** One value of {@code optionCodes}. */
    static final RequiredInput OPTION_CODE = RequiredInput.text("optionCodes", "选项编码", "访谈没有列出选项",
            "大写字母或数字开头，只含大写字母、数字、下划线，最长 " + MAX_OPTION_CODE + " 位", MAX_OPTION_CODE);
    /** One row of {@code fields}: every component of {@link FieldInput}, with the command's per-type shape rules. */
    static final List<RequiredInput> FIELD_FIELDS = List.of(
            RequiredInput.text("fieldKey", "字段标识", WHY_NO_FIELDS,
                    "小写字母开头，只含小写字母、数字、下划线，最长 " + MAX_KEY + " 位，同一表单内不重复", MAX_KEY),
            RequiredInput.text("label", "字段中文名", WHY_NO_FIELDS, "1–" + MAX_TITLE + " 字", MAX_TITLE),
            RequiredInput.oneOf("fieldType", "字段类型", WHY_NO_FIELDS,
                    String.join("、", Arrays.stream(FieldType.values()).map(Enum::name).toList())
                            + " 之一；选定后按类型补齐长度、范围、单位或选项", FIELD_TYPE_OPTIONS),
            RequiredInput.bool("required", "是否必填", WHY_NO_FIELDS, "是 / 否"),
            RequiredInput.integer("maxLength", "最多能填多少字", WHY_NO_FIELDS,
                    "选 TEXT 时必填 1–" + MAX_TEXT_LENGTH + "；其他类型留空", 1, MAX_TEXT_LENGTH).allowNull(),
            RequiredInput.decimal("minValue", "最小值", WHY_NO_FIELDS,
                    "选 INTEGER、DECIMAL、QUANTITY 时必填且不大于最大值，其他类型留空；整数部分不超过 12 位、小数不超过 6 位",
                    null, null).allowNull(),
            RequiredInput.decimal("maxValue", "最大值", WHY_NO_FIELDS,
                    "选 INTEGER、DECIMAL、QUANTITY 时必填且不小于最小值，其他类型留空；整数部分不超过 12 位、小数不超过 6 位",
                    null, null).allowNull(),
            RequiredInput.integer("decimalScale", "保留几位小数", WHY_NO_FIELDS,
                    "选 DECIMAL 或 QUANTITY 时填 0–" + MAX_SCALE + "；其他类型留空", 0, MAX_SCALE).allowNull(),
            RequiredInput.text("unitCode", "单位编码", WHY_NO_FIELDS,
                    "只有选 QUANTITY 时填写（字母开头的单位编码，如 kg，最长 " + MAX_UNIT_CODE + " 位）；其他类型留空",
                    MAX_UNIT_CODE).allowNull(),
            RequiredInput.values("optionCodes", "有哪些选项", WHY_NO_FIELDS,
                    "只有选 SINGLE_SELECT 或 MULTI_SELECT 时填 1–" + MAX_OPTIONS + " 个不重复的选项编码（合计不超过 "
                            + MAX_OPTION_CODES_WIDTH + " 字符）；其他类型留空",
                    "选项", 0, MAX_OPTIONS, OPTION_CODE).allowNull(),
            RequiredInput.text("helpText", "填写说明", WHY_NO_FIELDS, "不超过 " + MAX_HELP_TEXT + " 字，可以留空", MAX_HELP_TEXT)
                    .allowNull());

    private final ModuleConfigDraftRepository moduleDrafts;
    private final ModuleConfigDraftItemRepository moduleItems;

    public FormsDraftProposer(ModuleConfigDraftRepository moduleDrafts, ModuleConfigDraftItemRepository moduleItems) {
        this.moduleDrafts = moduleDrafts;
        this.moduleItems = moduleItems;
    }

    @Override
    public String capabilityId() {
        return FormSchemaDraftCommand.CAPABILITY_ID;
    }

    @Override
    public Proposed propose(Source source) {
        ModuleConfigDraft module = UpstreamDraftPins.latestModuleConfig(moduleDrafts, source).orElse(null);
        if (module == null) {
            return Proposed.blocked(AlertsDraftProposer.UPSTREAM_MISSING,
                    "表单必须绑定本蓝图同一版本的模块配置草稿，但还没有写模块配置草稿",
                    "先写模块配置草稿");
        }
        var blueprint = source.blueprint();
        ApplyAIInitRequest payload = blueprint.getSourcePayload();
        List<String> enabled = UpstreamDraftPins.enabledModules(moduleItems, source.factoryId(), module.getId());
        List<Option> modules = AlertsDraftProposer.moduleOptions(enabled);
        List<FormInput> forms = new ArrayList<>();
        List<RequiredInput> inputs = new ArrayList<>();
        List<Evidence> evidence = new ArrayList<>();
        evidence.add(new Evidence("moduleConfig", module.getId() + " / " + module.getDesiredDigest()));
        String reason = "依据建厂蓝图 v" + blueprint.getVersion() + "：访谈的表单模板";

        List<ApplyAIInitRequest.SchemaItem> schemas = payload.getSchemas() == null ? List.of() : payload.getSchemas();
        for (int i = 0; i < schemas.size(); i++) {
            ApplyAIInitRequest.SchemaItem schema = schemas.get(i);
            if (schema == null) continue;
            String at = "forms[" + forms.size() + "].";
            String title = schema.getEntityName() == null ? "" : schema.getEntityName().trim();
            String name = "“" + (title.isEmpty() ? schema.getEntityType() : title) + "”";
            evidence.add(new Evidence("sourcePayload.schemas[" + i + "]", schema.getEntityType() + " / " + title));
            if (title.isEmpty()) {
                inputs.add(RequiredInput.text(at + "title", "表单 " + schema.getEntityType() + " 叫什么",
                        "访谈的表单模板没有名称", "1–" + MAX_TITLE + " 字", MAX_TITLE));
            }
            inputs.add(RequiredInput.oneOf(at + "moduleId", name + "归哪个模块",
                    "建厂访谈没有把表单分到模块",
                    "模块配置草稿中已启用的模块之一：" + String.join("、", enabled), modules));
            inputs.add(RequiredInput.oneOf(at + "retentionClass", name + "属于哪类记录",
                    "建厂访谈没有问记录的保存要求", RETENTION_CONSTRAINT, RETENTION_OPTIONS));
            inputs.add(RequiredInput.oneOf(at + "responsibleRole", name + "由谁负责填写",
                    "建厂访谈没有问表单负责人",
                    String.join("、", Arrays.stream(ResponsibleRole.values()).map(Enum::name).toList()) + " 之一",
                    RESPONSIBLE_OPTIONS));

            List<Map<String, Object>> rawFields = schema.getFields() == null ? List.of() : schema.getFields();
            List<FieldInput> fields = new ArrayList<>();
            for (Map<String, Object> raw : rawFields) {
                if (raw == null) continue;
                fields.add(field(at + "fields[" + fields.size() + "].", name, raw, inputs));
            }
            if (fields.isEmpty()) {
                inputs.add(RequiredInput.list(at + "fields", name + "有哪些字段",
                        "访谈的表单模板没有字段", FIELDS_CONSTRAINT, "字段", MIN_FIELDS, MAX_FIELDS, FIELD_FIELDS));
            }
            forms.add(new FormInput(schema.getEntityType(), title.isEmpty() ? null : title, null, null, null,
                    fields.isEmpty() ? null : fields,
                    reason + " " + schema.getEntityType() + (title.isEmpty() ? "" : "「" + title + "」")));
        }

        ApplyAIInitRequest.SuggestedData data = payload.getSuggestedData();
        if (data != null && data.getCodeSegments() != null) {
            for (int i = 0; i < data.getCodeSegments().size(); i++) {
                ApplyAIInitRequest.CodeSegmentItem segment = data.getCodeSegments().get(i);
                if (segment == null) continue;
                evidence.add(new Evidence("sourcePayload.suggestedData.codeSegments[" + i + "]",
                        AlertsDraftProposer.NOT_CARRIED + segment.getCode() + " " + segment.getLabel()
                                + " 是物料编码分段，不是表单，表单草稿里没有对应项"));
            }
        }
        if (enabled.isEmpty()) {
            // Every form's module — a proposed one's or a row the user adds — would be asked with nothing to choose from.
            return Proposed.blocked("BLUEPRINT_DRAFT_NO_ENABLED_MODULE",
                    "表单必须归到模块配置草稿里已启用的模块，但模块配置草稿 " + module.getId() + " 没有启用任何模块",
                    "先在模块配置草稿里启用要用的模块");
        }
        if (forms.isEmpty()) {
            inputs.add(RequiredInput.list("forms", "要建哪些表单",
                    "建厂蓝图里没有表单模板", MIN_FORMS + "–" + MAX_FORMS + " 张表单，表单编码不能重复", "表单",
                    MIN_FORMS, MAX_FORMS, formFields(modules, enabled)));
        }

        DraftRequest request = new DraftRequest(source.factoryId(), blueprint.getId(), blueprint.getVersion(),
                source.sourceDigest(), new ModuleConfigPin(module.getId(), module.getDesiredDigest()),
                forms.isEmpty() ? null : forms, null, null, 0L);
        return Proposed.single(capabilityId(), "表单", request, inputs, evidence);
    }

    private static FieldInput field(String at, String form, Map<String, Object> raw, List<RequiredInput> inputs) {
        String rawKey = text(raw.get("name"));
        String label = text(raw.get("label"));
        String type = text(raw.get("type")).toLowerCase(Locale.ROOT);
        String unit = text(raw.get("unit"));
        String fieldName = form + "的“" + (label.isEmpty() ? rawKey : label) + "”";

        String key = rawKey.toLowerCase(Locale.ROOT);
        if (!key.matches(FIELD_KEY)) {
            inputs.add(RequiredInput.text(at + "fieldKey", fieldName + "的字段标识",
                    rawKey.isEmpty() ? "访谈的字段没有英文名" : "访谈给的字段名“" + rawKey + "”不是合法标识",
                    "小写字母开头，只含小写字母、数字、下划线，最长 " + MAX_KEY + " 位，同一表单内不重复", MAX_KEY));
            key = null;
        }
        if (label.isEmpty()) {
            inputs.add(RequiredInput.text(at + "label", fieldName + "的中文名",
                    "访谈的字段没有中文标签", "1–" + MAX_TITLE + " 字", MAX_TITLE));
        }
        Object required = raw.get("required");
        if (!(required instanceof Boolean)) {
            inputs.add(RequiredInput.bool(at + "required", fieldName + "是否必填",
                    "访谈没有说明这个字段是否必填", "是 / 否"));
        }

        FieldType fieldType = EXACT_TYPES.get(type);
        if (fieldType == FieldType.TEXT) {
            inputs.add(RequiredInput.integer(at + "maxLength", fieldName + "最多能填多少字",
                    "访谈没有问文本长度", "1–" + MAX_TEXT_LENGTH, 1, MAX_TEXT_LENGTH));
        } else if ("number".equals(type)) {
            inputs.add(RequiredInput.oneOf(at + "fieldType", fieldName + "是整数、小数还是带单位的数量",
                    "访谈只说是数字，没说是哪种", "INTEGER、DECIMAL、QUANTITY 之一", NUMBER_TYPE_OPTIONS));
            inputs.add(RequiredInput.decimal(at + "minValue", fieldName + "最小能填多少",
                    "访谈没有问数值范围", "数字，不大于最大值；整数部分不超过 12 位、小数不超过 6 位", null, null));
            inputs.add(RequiredInput.decimal(at + "maxValue", fieldName + "最大能填多少",
                    "访谈没有问数值范围", "数字，不小于最小值；整数部分不超过 12 位、小数不超过 6 位", null, null));
            inputs.add(RequiredInput.integer(at + "decimalScale", fieldName + "保留几位小数",
                    "访谈没有问小数位", "选 INTEGER 时留空；选 DECIMAL 或 QUANTITY 时填 0–" + MAX_SCALE, 0, MAX_SCALE).allowNull());
            inputs.add(RequiredInput.text(at + "unitCode", fieldName + "的单位编码",
                    unit.isEmpty() ? "访谈没有给单位" : "访谈给的单位是“" + unit + "”，只有选数量时才需要，且必须写成单位编码",
                    "只有选 QUANTITY 时填写（字母开头的单位编码，如 kg，最长 " + MAX_UNIT_CODE + " 位）；其他类型留空",
                    MAX_UNIT_CODE).allowNull());
        } else if ("enum".equals(type)) {
            inputs.add(RequiredInput.oneOf(at + "fieldType", fieldName + "是单选还是多选",
                    "访谈只说是选项，没说单选还是多选", "SINGLE_SELECT、MULTI_SELECT 之一", SELECT_TYPE_OPTIONS));
            // The option codes are the user's own vocabulary, not a closed set: a list of plain values.
            inputs.add(RequiredInput.values(at + "optionCodes", fieldName + "有哪些选项",
                    "访谈没有列出选项",
                    "1–" + MAX_OPTIONS + " 个不重复的选项编码，合计不超过 " + MAX_OPTION_CODES_WIDTH + " 字符",
                    "选项", 1, MAX_OPTIONS, OPTION_CODE));
        } else if (fieldType == null) {
            inputs.add(RequiredInput.oneOf(at + "fieldType", fieldName + "是什么类型",
                    type.isEmpty() ? "访谈没有给字段类型" : "访谈给的类型“" + type + "”不在表单字段类型里",
                    String.join("、", Arrays.stream(FieldType.values()).map(Enum::name).toList())
                            + " 之一；选定后按类型补齐长度、范围、单位或选项", FIELD_TYPE_OPTIONS));
        }
        return new FieldInput(key, label.isEmpty() ? null : label, fieldType,
                required instanceof Boolean value && value, null, null, null, null, null, List.of(), null);
    }

    /**
     * One row of {@code forms}: every component of {@link FormInput}; {@code fields} is a nested list.
     * The module choices are the pinned module DRAFT's enabled modules, so the list is built per proposal.
     */
    static List<RequiredInput> formFields(List<Option> modules, List<String> enabled) {
        String why = "建厂蓝图里没有表单模板";
        return List.of(
                RequiredInput.text("formCode", "表单编码", why,
                        "大写字母或数字开头，只含大写字母、数字、下划线、点或连字符，最长 " + MAX_KEY + " 位；草稿内不重复", MAX_KEY),
                RequiredInput.text("title", "表单名称", why, "1–" + MAX_TITLE + " 字", MAX_TITLE),
                RequiredInput.oneOf("moduleId", "归哪个模块", why,
                        "模块配置草稿中已启用的模块之一：" + String.join("、", enabled), modules),
                RequiredInput.oneOf("retentionClass", "属于哪类记录", why, RETENTION_CONSTRAINT, RETENTION_OPTIONS),
                RequiredInput.oneOf("responsibleRole", "由谁负责填写", why,
                        String.join("、", Arrays.stream(ResponsibleRole.values()).map(Enum::name).toList()) + " 之一",
                        RESPONSIBLE_OPTIONS),
                RequiredInput.list("fields", "有哪些字段", why, FIELDS_CONSTRAINT, "字段", MIN_FIELDS, MAX_FIELDS, FIELD_FIELDS),
                RequiredInput.text("changeReason", "建这张表单的依据", why, "1–" + MAX_REASON + " 字", MAX_REASON));
    }

    private static List<Option> fieldTypes(FieldType... types) {
        return Arrays.stream(types).map(type -> new Option(type.name(), FIELD_TYPE_LABELS.get(type))).toList();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
