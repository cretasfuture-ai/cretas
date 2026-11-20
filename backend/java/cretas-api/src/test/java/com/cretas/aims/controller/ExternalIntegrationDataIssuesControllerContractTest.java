package com.cretas.aims.controller;

import com.cretas.aims.entity.integration.ExternalSourceSnapshot;
import com.cretas.aims.entity.integration.FactoryExternalIntegration;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.integration.ExternalSourceSnapshotRepository;
import com.cretas.aims.repository.integration.FactoryExternalIntegrationRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.integration.ExternalIntegrationService;
import com.cretas.aims.service.unit.UnitContractService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 数据需修正清单从真实 controller 入口走到真实 service + 检测器 (仓库层 mock): 授权 / 租户 / 读不到 / 三态。 */
class ExternalIntegrationDataIssuesControllerContractTest {

    static final String URL = "/api/mobile/{f}/external-integrations/blacklake/data-issues";

    final FactoryExternalIntegrationRepository integrations = mock(FactoryExternalIntegrationRepository.class);
    final ExternalSourceSnapshotRepository snapshots = mock(ExternalSourceSnapshotRepository.class);
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();
        var service = new ExternalIntegrationService(integrations, snapshots, mock(ProductionReportRepository.class),
                mock(WorkProcessTaskRepository.class), mock(WorkProcessRepository.class), mapper, mock(UnitContractService.class));
        mvc = MockMvcBuilders.standaloneSetup(new ExternalIntegrationController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
        when(integrations.findByFactoryIdAndSystem(any(), any())).thenReturn(Optional.empty());
        when(integrations.findByFactoryIdAndSystem("LSM", "blacklake")).thenReturn(Optional.of(
                FactoryExternalIntegration.builder().factoryId("LSM").system("blacklake").status("ACTIVE").build()));
    }

    private void snapshot(String factoryId, String payload) {
        when(snapshots.findFirstByFactoryIdAndSystemAndDatasetOrderByCollectedAtDesc(
                eq(factoryId), eq("blacklake"), eq("report_output")))
                .thenReturn(Optional.of(ExternalSourceSnapshot.builder().id(9L).factoryId(factoryId).system("blacklake")
                        .dataset("report_output").collectedAt(LocalDateTime.now()).receivedAt(LocalDateTime.now())
                        .rowCount(1).payload(payload).payloadSha256("x").build()));
    }

    private static String r(String date, String product, String process, String qty, String unit) {
        return "{\"reportDate\":\"" + date + "\",\"productName\":\"" + product + "\",\"processName\":\"" + process
                + "\",\"unit\":\"" + unit + "\",\"goodQty\":\"" + qty + "\"}";
    }

    @Test
    void realShapesComeBackWithTypeCountsAndSuggestions() throws Exception {
        snapshot("LSM", "[" + r("2026-11-16", "香嫩牛肉丝", "砍排修油", "1771.73", "kg/盒") + ","
                + r("2026-11-07", "虎皮鸡爪", "熟制", "0", "kg") + "]");
        mvc.perform(get(URL, "LSM")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.issues[0].type").value("COMPOUND_UNIT"))
                .andExpect(jsonPath("$.data.issues[0].unit").value("kg/盒"))
                .andExpect(jsonPath("$.data.issues[1].type").value("ZERO_QTY"))
                .andExpect(jsonPath("$.data.counts[?(@.type=='ZERO_QTY')].count").value(1))
                .andExpect(jsonPath("$.data.counts[?(@.type=='MIXED_UNIT')].count").value(0));
    }

    @Test
    void cleanSnapshotIsAvailableWithZeroIssues() throws Exception {
        snapshot("LSM", "[" + r("2026-11-16", "酱鸭腿", "腌制", "10", "kg") + "]");
        mvc.perform(get(URL, "LSM")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.issues").isEmpty());
    }

    @Test
    void noSnapshotIsNotConnectedNotZero() throws Exception {
        when(snapshots.findFirstByFactoryIdAndSystemAndDatasetOrderByCollectedAtDesc(any(), any(), any()))
                .thenReturn(Optional.empty());
        mvc.perform(get(URL, "LSM")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(false))
                .andExpect(jsonPath("$.data.reason").value("外部数据未接入（找平台）"));
    }

    @Test
    void unreadableSnapshotIsAnErrorNotZeroIssues() throws Exception {
        snapshot("LSM", "not json");
        mvc.perform(get(URL, "LSM")).andExpect(status().is5xxServerError());
    }

    @Test
    void factoryWithoutAuthorizationGets404AndNeverTouchesSnapshots() throws Exception {
        mvc.perform(get(URL, "F006")).andExpect(status().isNotFound());
        verifyNoInteractions(snapshots);
    }
}
