package com.cretas.aims.controller;

import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.shortage.ShortageCaseActionRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.shortage.ShortageCaseActionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 生成采购草稿 / 做决定的操作人取 JWT 验过的身份, 别厂的 token 进不来; 决定的原因在入口就必填。 */
class ShortageCaseActionControllerContractTest {

    private final ShortageCaseActionService service = mock(ShortageCaseActionService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ShortageCaseActionController(service,
                        mock(ShortageCaseActionRepository.class), mock(ShortageCaseRepository.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        when(service.createProcurementDraft(any(), any(), any(), any())).thenReturn(
                new ShortageCaseActionService.DraftResult("PO-1", "PO-NO-1", false, BigDecimal.TEN, "kg", List.of()));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String factoryId) {
        return b.requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE,
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 9L, factoryId, "procurement_manager"));
    }

    @Test
    void aBuyerOfThisFactoryPlacesTheDraftAsThemselves() throws Exception {
        mvc.perform(as(post("/api/mobile/F1/shortage-cases/C1/procurement-drafts"), "F1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"materialTypeId\":\"M1\"}"))
                .andExpect(status().isOk());

        verify(service).createProcurementDraft("F1", "C1", "M1", new ActorRef(AuthenticatedActorType.FACTORY_USER, 9L));
    }

    @Test
    void anotherFactorysTokenAndAMissingIdentityAreRefused() throws Exception {
        mvc.perform(as(post("/api/mobile/F1/shortage-cases/C1/procurement-drafts"), "F2")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"materialTypeId\":\"M1\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/mobile/F1/shortage-cases/C1/procurement-drafts").requestAttr("userId", 9L)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"materialTypeId\":\"M1\"}"))
                .andExpect(status().isUnauthorized());

        verify(service, never()).createProcurementDraft(anyString(), anyString(), anyString(), any());
    }

    @Test
    void aDecisionWithoutAReasonIsRejectedAtTheEntry() throws Exception {
        mvc.perform(as(post("/api/mobile/F1/shortage-cases/C1/decision"), "F1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"CANCEL\",\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());

        verify(service, never()).decide(anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void anUnknownDecisionIsRejected() throws Exception {
        mvc.perform(as(post("/api/mobile/F1/shortage-cases/C1/decision"), "F1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"DELETE\",\"reason\":\"x\"}"))
                .andExpect(status().isBadRequest());

        verify(service, never()).decide(eq("F1"), anyString(), any(), any(), any(), any());
    }
}
