package com.cretas.aims.controller;

import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.shortage.ShortageCase;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.shortage.ShortageCaseEventRepository;
import com.cretas.aims.repository.shortage.ShortageCaseLineRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.service.shortage.ShortageCaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 缺料实例接口只认本厂; 缺口解决和订单交付分开给; 已结束的实例不再刷新。 */
class ShortageCaseControllerContractTest {

    private final ShortageCaseRepository cases = mock(ShortageCaseRepository.class);
    private final ShortageCaseLineRepository lines = mock(ShortageCaseLineRepository.class);
    private final ShortageCaseEventRepository events = mock(ShortageCaseEventRepository.class);
    private final SalesOrderRepository orders = mock(SalesOrderRepository.class);
    private final ShortageCaseService service = mock(ShortageCaseService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ShortageCaseController(cases, lines, events, orders, service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        when(cases.findByIdAndFactoryId(anyString(), anyString())).thenReturn(Optional.empty());
        when(lines.findByCaseIdOrderByIdAsc(anyString())).thenReturn(List.of());
        when(events.findByCaseIdOrderByIdAsc(anyString())).thenReturn(List.of());
    }

    private ShortageCase stored(ShortageCase.Stage stage) {
        ShortageCase c = new ShortageCase();
        c.setId("C1");
        c.setFactoryId("F1");
        c.setSourceType(ShortageCase.SourceType.SALES_ORDER);
        c.setSourceId("SO-1");
        c.setStage(stage);
        c.setOwnerRole("procurement_manager");
        c.setInputFingerprint("fp");
        c.setLastRefreshedAt(LocalDateTime.of(2026, 11, 3, 8, 0));
        c.setLastRefreshReason("SO_FINANCE_APPROVED");
        when(cases.findByIdAndFactoryId("C1", "F1")).thenReturn(Optional.of(c));
        // 按裸 id 也查得到: 只有按工厂取才挡得住别厂 (退回 findById 的实现会把 F1 的实例给 F2 看)
        when(cases.findById("C1")).thenReturn(Optional.of(c));
        SalesOrder so = new SalesOrder();
        so.setId("SO-1");
        so.setFactoryId("F1");
        so.setOrderNumber("SO-20261103-0001");
        when(orders.findById("SO-1")).thenReturn(Optional.of(so));
        return c;
    }

    @Test
    void anotherFactorysCaseIs404AndIsNeverRefreshed() throws Exception {
        stored(ShortageCase.Stage.DETECTED);

        mvc.perform(get("/api/mobile/F2/shortage-cases/C1")).andExpect(status().isNotFound());
        mvc.perform(post("/api/mobile/F2/shortage-cases/C1/refresh")).andExpect(status().isNotFound());

        verify(service, never()).refreshForSalesOrder(anyString(), anyString(), anyString());
    }

    @Test
    void detailCarriesGapResolutionAndOrderDeliverySeparately() throws Exception {
        stored(ShortageCase.Stage.GAP_RESOLVED).setGapResolvedAt(LocalDateTime.of(2026, 11, 3, 9, 0));

        mvc.perform(get("/api/mobile/F1/shortage-cases/C1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.case.stage").value("GAP_RESOLVED"))
                .andExpect(jsonPath("$.data.case.gapResolvedAt").exists())
                .andExpect(jsonPath("$.data.case.salesOrderNumber").value("SO-20261103-0001"))
                .andExpect(jsonPath("$.data.case.salesOrderStatus").exists());
    }

    @Test
    void aFinishedCaseIsNotRefreshed() throws Exception {
        stored(ShortageCase.Stage.CANCELLED);

        mvc.perform(post("/api/mobile/F1/shortage-cases/C1/refresh")).andExpect(status().isConflict());

        verify(service, never()).refreshForSalesOrder(anyString(), anyString(), anyString());
    }

    @Test
    void refreshRunsAgainstThisFactorysOrder() throws Exception {
        stored(ShortageCase.Stage.DETECTED);
        when(service.refreshForSalesOrder(any(), any(), any())).thenReturn(Optional.empty());

        mvc.perform(post("/api/mobile/F1/shortage-cases/C1/refresh")).andExpect(status().isOk());

        verify(service).refreshForSalesOrder("F1", "SO-1", "MANUAL_REFRESH");
    }
}
