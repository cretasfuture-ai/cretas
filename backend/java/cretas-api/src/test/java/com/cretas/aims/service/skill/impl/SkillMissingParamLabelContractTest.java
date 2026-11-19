package com.cretas.aims.service.skill.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 质检数字专员 E: 缺参追问回「还需要以下信息：batchNumber、customerId」外露字段名。
 * 给用户看的称呼必须是中文, 认不出的字段统称「必要信息」, 不拿字段名顶替。
 */
class SkillMissingParamLabelContractTest {

    @Test
    void knownFieldsGetChineseLabels() {
        assertThat(SkillExecutorImpl.parameterLabel("batchNumber")).isEqualTo("批次号");
        assertThat(SkillExecutorImpl.parameterLabel("customerId")).isEqualTo("客户");
        assertThat(SkillExecutorImpl.parameterLabel("supplierId")).isEqualTo("供应商");
    }

    @Test
    void unknownFieldNeverLeaksItsName() {
        assertThat(SkillExecutorImpl.parameterLabel("someInternalField")).isEqualTo("必要信息");
        assertThat(SkillExecutorImpl.parameterLabel(null)).isEqualTo("必要信息");
    }
}
