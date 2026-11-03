package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.blueprint.draftentry.CapabilityDraftProposer.Option;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** 建厂草稿下拉不许再露英文代码：标签是中文，存的值仍是枚举常量名。 */
class DraftEnumLabelsTest {

    private static final Pattern RAW_CODE = Pattern.compile("^[A-Z][A-Z0-9_ ()]*$");

    /** 每个草稿命令的模型类里，凡是下拉用到的枚举。 */
    private static final List<String> MODEL_CLASSES = List.of(
            "allergenlabeldraft.AllergenLabelDraftModels", "contractmaterialdraft.ContractMaterialPolicyDraftModels",
            "deviationcapadraft.DeviationCapaTemplateDraftModels", "emergencydefensedraft.EmergencyDefensePlanDraftModels",
            "environmentalmonitoringdraft.EnvironmentalMonitoringDraftModels", "equipmentplandraft.EquipmentCalibrationPlanDraftModels",
            "hazardanalysisdraft.HazardAnalysisDraftModels", "hygieniczonedraft.HygienicZoneDraftModels",
            "qualityreleasedraft.QualityReleaseTemplateDraftModels", "sanitationcipdraft.SanitationCipDraftModels",
            "supplierreceivingdraft.SupplierReceivingPolicyDraftModels", "tracerecalldraft.TraceRecallPlanDraftModels");

    @Test
    void everyEnumConstantUsedByTheDraftDropdownsHasAChineseLabel() throws Exception {
        List<String> missing = new ArrayList<>();
        for (String name : MODEL_CLASSES) {
            Class<?> models = Class.forName("com.cretas.aims.service." + name);
            for (Class<?> nested : models.getDeclaredClasses()) {
                if (!nested.isEnum()) continue;
                for (Object constant : nested.getEnumConstants()) {
                    Enum<?> value = (Enum<?>) constant;
                    String label = DraftEnumLabels.label(value);
                    if (!DraftEnumLabels.hasLabel(value) || RAW_CODE.matcher(label).matches()) {
                        missing.add(nested.getSimpleName() + "." + value.name() + " → " + label);
                    }
                }
            }
        }
        assertThat(missing).as("没有中文名的枚举常量").isEmpty();
    }

    @Test
    void everyStaticOptionListInTheProposersShowsChineseAndKeepsTheStoredValue() throws Exception {
        List<String> bad = new ArrayList<>();
        int checked = 0;
        for (Class<?> proposer : List.of(AccountsDraftProposer.class, AlertsDraftProposer.class, CriticalControlsDraftProposer.class,
                FormsDraftProposer.class, MaterialsDraftProposer.class, ProcessesDraftProposer.class, WarehousesDraftProposer.class,
                BomDraftProposer.class, ProductsDraftProposer.class)) {
            for (Field field : proposer.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || !List.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                Object value = field.get(null);
                if (!(value instanceof List<?> list) || list.isEmpty() || !(list.get(0) instanceof Option)) continue;
                for (Object item : list) {
                    Option option = (Option) item;
                    checked++;
                    if (RAW_CODE.matcher(option.label()).matches()) bad.add(proposer.getSimpleName() + "." + field.getName() + ": " + option.value() + " → " + option.label());
                }
            }
        }
        assertThat(checked).as("确实检查到了下拉选项").isGreaterThan(20);
        assertThat(bad).isEmpty();
    }

    @Test
    void theStoredValueIsStillTheConstantName() {
        var option = DraftEnumLabels.options(com.cretas.aims.service.supplierreceivingdraft.SupplierReceivingPolicyDraftModels.AcceptanceMode.class);
        assertThat(option).extracting(Option::value).containsExactly("STANDARD", "HIGH_RISK", "NEW_SUPPLIER");
        assertThat(option).extracting(Option::label).containsExactly("标准验收", "高风险", "新供应商");
    }
}
