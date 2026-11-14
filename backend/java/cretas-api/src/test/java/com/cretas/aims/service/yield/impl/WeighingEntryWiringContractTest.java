package com.cretas.aims.service.yield.impl;

import com.cretas.aims.dto.yield.YieldReportRequest;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.yield.WeighingEntryRules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 迁移 P5: 生产上真正的称重写入口 {@code YieldReportServiceImpl#submitReport} 确实调用了 R36 / 皮重校验,
 * 而且在查任务、落库之前就拦 (被拦的请求什么都不写)。阳性对照: 时间合法时走过了校验, 进入下一道 (查工序任务)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WeighingEntryWiringContractTest {

    @Mock private ProductionReportRepository reportRepo;
    @Mock private WorkProcessTaskRepository taskRepo;
    @InjectMocks private YieldReportServiceImpl service;

    @BeforeEach
    void fixClock() {
        service.setClock(Clock.fixed(
                ZonedDateTime.of(2026, 11, 14, 10, 0, 0, 0, WeighingEntryRules.FACTORY_ZONE).toInstant(),
                WeighingEntryRules.FACTORY_ZONE));
    }

    private static YieldReportRequest req(String start, String end, String tare) {
        YieldReportRequest r = new YieldReportRequest();
        r.setWorkProcessTaskId(11L);
        r.setInputQuantity(new BigDecimal("100"));
        r.setOutputQuantity(new BigDecimal("80"));
        r.setOutputUnit("kg");
        r.setBusinessDate(java.time.LocalDate.of(2026, 11, 14));
        r.setTareWeight(tare == null ? null : new BigDecimal(tare));
        YieldReportRequest.LaborSegment s = new YieldReportRequest.LaborSegment();
        s.setStartTime(start);
        s.setEndTime(end);
        r.setLaborSegments(List.of(s));
        return r;
    }

    @Test
    @DisplayName("阴性: 结束时间晚于现在 1 小时 ⇒ 400 WEIGHING_TIME_IN_FUTURE, 没查任务也没落库")
    void futureTimeIsRejectedBeforeAnything() {
        assertThatThrownBy(() -> service.submitReport("F1", 5L, 9L, req("09:00", "11:00", null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("WEIGHING_TIME_IN_FUTURE"));
        verify(taskRepo, never()).findByFactoryIdAndId(anyString(), anyLong());
        verify(reportRepo, never()).save(any());
    }

    @Test
    @DisplayName("阴性: 皮重为负 ⇒ 400 WEIGHING_TARE_NEGATIVE, 没落库")
    void negativeTareIsRejected() {
        assertThatThrownBy(() -> service.submitReport("F1", 5L, 9L, req("09:00", "09:30", "-1")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("WEIGHING_TARE_NEGATIVE"));
        verify(reportRepo, never()).save(any());
    }

    @Test
    @DisplayName("阴性: 皮重 0.125 (超过 2 位小数) ⇒ 400 WEIGHING_TARE_SCALE, 没查任务也没落库")
    void tareWithThreeDecimalsIsRejected() {
        assertThatThrownBy(() -> service.submitReport("F1", 5L, 9L, req("09:00", "09:30", "0.125")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("WEIGHING_TARE_SCALE"));
        verify(taskRepo, never()).findByFactoryIdAndId(anyString(), anyLong());
        verify(reportRepo, never()).save(any());
    }

    @Test
    @DisplayName("阴性: 良品单位是「盒」还带皮重 ⇒ 400 WEIGHING_TARE_UNIT_NOT_MASS, 没落库")
    void tareWithCountOutputIsRejected() {
        YieldReportRequest r = req("09:00", "09:30", "1.5");
        r.setOutputUnit("盒");
        assertThatThrownBy(() -> service.submitReport("F1", 5L, 9L, r))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("WEIGHING_TARE_UNIT_NOT_MASS"));
        verify(reportRepo, never()).save(any());
    }

    @Test
    @DisplayName("阳性对照: 时间与皮重都合法 ⇒ 通过 P5 校验, 走到查工序任务 (这里任务不存在 ⇒ 404, 不是 P5 的错)")
    void validEntryPassesP5Checks() {
        assertThatThrownBy(() -> service.submitReport("F1", 5L, 9L, req("09:00", "09:30", "1.5")))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isNotIn("WEIGHING_TIME_IN_FUTURE", "WEIGHING_TARE_NEGATIVE",
                            "WEIGHING_TARE_EXCEEDS_GROSS", "WEIGHING_TARE_SCALE", "WEIGHING_TARE_UNIT_NOT_MASS");
                    assertThat(e.getMessage()).contains("工序任务不存在");
                });
        verify(taskRepo).findByFactoryIdAndId("F1", 11L);
    }
}
