package com.cretas.aims.controller;

import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.shortage.RescheduleProposal;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.shortage.RescheduleProposalRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.shortage.ShortageRescheduleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.*;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 重排的权限走真实的 {@link PermissionInterceptor} (不是只看注解): 排程写权限 (scheduling:read_write) 与
 * 生产写权限一样能生成 / 应用方案 —— 与已有的 39 条排程路由口径一致; 两样都没有就 403, 服务一次都不被调用;
 * 只有读权限能看方案列表、不能生成。
 */
class ShortageReschedulePermissionContractTest {

    private final ShortageRescheduleService service = mock(ShortageRescheduleService.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final Set<String> granted = new HashSet<>();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ShortageRescheduleController(service,
                        mock(RescheduleProposalRepository.class), caseRepository(),
                        mock(com.cretas.aims.repository.shortage.ShortageCaseActionRepository.class),
                        OutcomeCheckStubs.unknown()))
                .addInterceptors(new PermissionInterceptor(permissions, users, new ObjectMapper()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        User u = new User();
        u.setId(7L);
        u.setFactoryId("F1");
        when(users.findById(7L)).thenReturn(Optional.of(u));
        when(permissions.hasAnyPermission(any(User.class), any(String[].class))).thenAnswer(i -> {
            List<String> asked = new ArrayList<>();
            for (Object a : Arrays.copyOfRange(i.getArguments(), 1, i.getArguments().length)) {
                if (a instanceof String[] arr) asked.addAll(Arrays.asList(arr));
                else if (a instanceof String s) asked.add(s);
            }
            return asked.stream().anyMatch(granted::contains);
        });
        RescheduleProposal p = new RescheduleProposal();
        p.setId("RP-1");
        p.setStatus(RescheduleProposal.Status.OPEN);
        p.setCandidates(List.of());
        p.setExpiresAt(LocalDateTime.now().plusMinutes(30));
        when(service.propose(any(), any(), any())).thenReturn(p);
        when(service.apply(any(), any(), any(), any(), any())).thenReturn(p);
    }

    private static ShortageCaseRepository caseRepository() {
        ShortageCaseRepository cases = mock(ShortageCaseRepository.class);
        com.cretas.aims.entity.shortage.ShortageCase c = new com.cretas.aims.entity.shortage.ShortageCase();
        c.setId("C1");
        c.setFactoryId("F1");
        when(cases.findByIdAndFactoryId("C1", "F1")).thenReturn(Optional.of(c));
        return cases;
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b) {
        return b.requestAttr("userId", 7L).requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE,
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 7L, "F1", "dispatcher"));
    }

    private MockHttpServletRequestBuilder propose() {
        return as(post("/api/mobile/F1/shortage-cases/C1/reschedule-proposals"));
    }

    private MockHttpServletRequestBuilder apply() {
        return as(post("/api/mobile/F1/shortage-cases/C1/reschedule-proposals/RP-1/apply"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"candidateKey\":\"SAME_LINE_POSTPONE\"}");
    }

    @Test
    void aSchedulingWriterCanProposeAndApply() throws Exception {
        granted.add("scheduling:read_write");

        mvc.perform(propose()).andExpect(status().isOk());
        mvc.perform(apply()).andExpect(status().isOk());

        verify(service).propose(eq("F1"), eq("C1"), any());
        verify(service).apply(eq("F1"), eq("C1"), eq("RP-1"), eq("SAME_LINE_POSTPONE"), any());
    }

    @Test
    void aProductionWriterCanProposeAndApplyToo() throws Exception {
        granted.add("production:read_write");

        mvc.perform(propose()).andExpect(status().isOk());
        mvc.perform(apply()).andExpect(status().isOk());
    }

    @Test
    void withoutEitherWritePermissionBothAreRefusedAndNothingRuns() throws Exception {
        granted.add("procurement:read_write");   // 别的模块的写权限不算

        mvc.perform(propose()).andExpect(status().isForbidden());
        mvc.perform(apply()).andExpect(status().isForbidden());

        verify(service, never()).propose(anyString(), anyString(), any());
        verify(service, never()).apply(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void aSchedulingReaderSeesTheProposalsButCannotCreateOne() throws Exception {
        granted.add("scheduling:read");

        mvc.perform(as(get("/api/mobile/F1/shortage-cases/C1/reschedule-proposals"))).andExpect(status().isOk());
        mvc.perform(propose()).andExpect(status().isForbidden());
        verify(service, never()).propose(anyString(), anyString(), any());
    }

    private static <T> T eq(T v) {
        return org.mockito.ArgumentMatchers.eq(v);
    }
}
