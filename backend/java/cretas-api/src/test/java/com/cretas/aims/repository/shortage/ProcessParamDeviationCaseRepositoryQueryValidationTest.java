package com.cretas.aims.repository.shortage;

import com.cretas.aims.entity.shortage.ProcessParamDeviationCaseDetail;
import com.cretas.aims.entity.shortage.ShortageCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Hibernate startup/query gate for the process-param source-specific repository. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackageClasses = ProcessParamDeviationCaseDetailRepository.class,
        includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = {ShortageCaseRepository.class, ProcessParamDeviationCaseDetailRepository.class}))
class ProcessParamDeviationCaseRepositoryQueryValidationTest {

    @Autowired private ShortageCaseRepository cases;
    @Autowired private ProcessParamDeviationCaseDetailRepository details;

    @Test
    void sourceKeyLockQueryAndEnumMappingExecuteAgainstRealJpaContext() {
        ShortageCase c = new ShortageCase();
        c.setFactoryId("Q-PARAM");
        c.setSourceType(ShortageCase.SourceType.PROCESS_PARAM_DEVIATION);
        c.setSourceId("yield-report:1:temp");
        c.setStage(ShortageCase.Stage.DETECTED);
        c.setOwnerRole("quality_manager");
        c.setGapLineCount(1);
        c.setInputFingerprint("fingerprint");
        c.setLastRefreshedAt(LocalDateTime.of(2026, 11, 6, 8, 0));
        c.setLastRefreshReason("TEST");
        c = cases.saveAndFlush(c);

        ProcessParamDeviationCaseDetail d = new ProcessParamDeviationCaseDetail();
        d.setCaseId(c.getId());
        d.setFactoryId("Q-PARAM");
        d.setSourceKey("yield-report:1:temp");
        d.setSourceKind(ProcessParamDeviationCaseDetail.SourceKind.YIELD_REPORT);
        d.setSourceReportId(1L);
        d.setProductTypeId("PT-QA");
        d.setParamKey("temp");
        d.setParamLabel("温度");
        d.setUnit("℃");
        d.setMeasuredValue(new BigDecimal("170"));
        d.setStandardValue(new BigDecimal("150"));
        d.setRangeMin(new BigDecimal("140"));
        d.setRangeMax(new BigDecimal("160"));
        d.setStatus("HIGH");
        d.setFactFingerprint("fingerprint");
        d.setObservedAt(LocalDateTime.of(2026, 11, 6, 8, 0));
        details.saveAndFlush(d);

        assertThat(details.findByFactoryIdAndSourceKey("Q-PARAM", "yield-report:1:temp"))
                .get().extracting(ProcessParamDeviationCaseDetail::getCaseId).isEqualTo(c.getId());
        assertThat(details.findByCaseIdAndFactoryId(c.getId(), "Q-PARAM")).isPresent();
        assertThat(cases.findOpenForUpdate("Q-PARAM", ShortageCase.SourceType.PROCESS_PARAM_DEVIATION,
                "yield-report:1:temp")).isPresent();
        assertThat(cases.findByFactoryIdAndSourceTypeAndSourceIdForUpdate("Q-PARAM",
                ShortageCase.SourceType.PROCESS_PARAM_DEVIATION, "yield-report:1:temp")).isPresent();
        assertThat(cases.findForUpdateByIdAndFactoryIdAndSourceTypeIn(c.getId(), "Q-PARAM",
                java.util.List.of(ShortageCase.SourceType.PROCESS_PARAM_DEVIATION))).isPresent();
        assertThat(details.findByCaseIdAndFactoryIdForUpdate(c.getId(), "Q-PARAM")).isPresent();
        assertThat(details.findByFactoryIdAndSourceKey("OTHER", "yield-report:1:temp")).isEmpty();
    }
}
