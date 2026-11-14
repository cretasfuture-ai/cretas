package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.service.foodsafety.timelimit.ProcessTimeLimitService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 缺口 16: 归属判不出时列表照常返回, 原因走 message + severity=WARNING (web 时限面板据此提示)。 */
class ProcessTimeLimitControllerViewTest {

    @Test
    void noticeTravelsAsWarningMessageWithTheList() {
        ApiResponse<List<ProcessTimeLimitService.SourceStatus>> res = ProcessTimeLimitController.view(
                new ProcessTimeLimitService.SourceStatusView(List.of(), "本计划缺客户信息，无法判定在制品归属"));
        assertThat(res.getSuccess()).isTrue();
        assertThat(res.getSeverity()).isEqualTo("WARNING");
        assertThat(res.getMessage()).isEqualTo("本计划缺客户信息，无法判定在制品归属");
        assertThat(res.getData()).isEmpty();
    }

    @Test
    void noNoticeMeansPlainSuccess() {
        ApiResponse<List<ProcessTimeLimitService.SourceStatus>> res = ProcessTimeLimitController.view(
                new ProcessTimeLimitService.SourceStatusView(List.of(), null));
        assertThat(res.getSuccess()).isTrue();
        assertThat(res.getSeverity()).isNull();
    }
}
