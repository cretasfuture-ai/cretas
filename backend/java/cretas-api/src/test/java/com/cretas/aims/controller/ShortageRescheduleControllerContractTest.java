package com.cretas.aims.controller;

import com.cretas.aims.entity.shortage.RescheduleProposal;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.shortage.RescheduleProposalRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.shortage.ShortageRescheduleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 重排的操作人取 JWT 验过的身份; 别厂 token 进不来; 没选候选在入口就 400; 方案过期如实 409。 */
class ShortageRescheduleControllerContractTest {

    private final ShortageRescheduleService service = mock(ShortageRescheduleService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ShortageRescheduleController(service,
                        mock(RescheduleProposalRepository.class), mock(ShortageCaseRepository.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        RescheduleProposal p = new RescheduleProposal();
        p.setId("RP-1");
        p.setStatus(RescheduleProposal.Status.OPEN);
        p.setCandidates(List.of());
        p.setExpiresAt(LocalDateTime.now().plusMinutes(30));
        when(service.propose(any(), any(), any())).thenReturn(p);
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String factoryId) {
        return b.requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE,
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 7L, factoryId, "production_manager"));
    }

    @Test
    void aPlannerOfThisFactoryProposesAsThemselvesAndSeesAChineseStatus() throws Exception {
        mvc.perform(as(post("/api/mobile/F1/shortage-cases/C1/reschedule-proposals"), "F1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.statusLabel").value("待确认"));

        verify(service).propose("F1", "C1", new ActorRef(AuthenticatedActorType.FACTORY_USER, 7L));
    }

    @Test
    void anotherFactorysTokenIsRefusedAndAMissingCandidateIsA400() throws Exception {
        mvc.perform(as(post("/api/mobile/F1/shortage-cases/C1/reschedule-proposals"), "F2"))
                .andExpect(status().isForbidden());
        mvc.perform(as(post("/api/mobile/F1/shortage-cases/C1/reschedule-proposals/RP-1/apply"), "F1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"candidateKey\":\" \"}"))
                .andExpect(status().isBadRequest());

        verify(service, never()).propose(anyString(), anyString(), any());
        verify(service, never()).apply(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void aStaleProposalComesBackAsA409() throws Exception {
        when(service.apply(any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException(409, "方案已过期: 排程任务在生成方案后被改动过").withHint("重新生成重排方案, 再确认"));

        mvc.perform(as(post("/api/mobile/F1/shortage-cases/C1/reschedule-proposals/RP-1/apply"), "F1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"candidateKey\":\"SAME_LINE_POSTPONE\"}"))
                .andExpect(status().isConflict());
    }
}
