package com.cretas.aims.service.skill.impl;

import com.cretas.aims.dto.skill.SkillResult;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

/** 2026-11-18 LSMDEMO: 单查 (indicator_code) + 对比 (indicator_codes) 两个工具都缺指标, 同一句追问被拼了两遍。 */
class SkillIndicatorClarifyOnceContractTest {

    @Test
    void missingIndicatorCodeIsAskedOnce() {
        SkillExecutorImpl exec = mock(SkillExecutorImpl.class, Mockito.CALLS_REAL_METHODS);
        SkillResult r = exec.missingParametersResult("smart-indicator-query",
                List.of("indicator_code", "indicator_codes"), List.of(), System.currentTimeMillis());
        String msg = r.getMessage();
        assertEquals(1, msg.split("请告诉我要查哪个指标", -1).length - 1, msg);
        assertFalse(msg.contains("indicator_code"), msg);
    }
}
