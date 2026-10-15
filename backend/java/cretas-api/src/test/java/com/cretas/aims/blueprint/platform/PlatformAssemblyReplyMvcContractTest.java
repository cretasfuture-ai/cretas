package com.cretas.aims.blueprint.platform;

import com.cretas.aims.controller.PlatformAssemblyInterviewController;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.*;
import com.cretas.aims.service.FactoryAssemblyConversationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Controller routing/typed delegation; signed identity is covered by the access/MVC gates. */
class PlatformAssemblyReplyMvcContractTest {
    @RestControllerAdvice static class Errors {
        @ExceptionHandler(BusinessException.class) ResponseEntity<String> error(BusinessException e) {
            return ResponseEntity.status(e.getCode()).body(e.getMessage());
        }
    }
    private static final String URL = "/api/platform/factory-assembly/factories/F1/ai-initialize/converse/S1/reply";

    @Test void platformReplyUsesOnlyIsolatedServiceWithTypedActorAndRequestId() throws Exception {
        var service = mock(FactoryAssemblyConversationService.class);
        var controller = new PlatformAssemblyInterviewController();
        ReflectionTestUtils.setField(controller, "assemblyConversations", service);
        var actor = new FactoryAssemblyConversationService.ConversationActor(AuthenticatedActorType.PLATFORM_ADMIN, 62L);
        when(service.continueConversation("F1", actor, "S1", "面包", "reply-62"))
                .thenReturn(FactoryAssemblyConversationService.ConversationResponse.builder().sessionId("S1").message("next").build());
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new Errors()).build();
        mvc.perform(post(URL).contentType("application/json")
                .requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE, new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN,62L,null,"super_admin"))
                .content("{\"userReply\":\"面包\",\"requestId\":\"reply-62\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.sessionId").value("S1"));
        verify(service).continueConversation("F1", actor, "S1", "面包", "reply-62");
    }

    @Test void factoryNamespaceOrMissingIdentityCannotReachIsolatedReply() throws Exception {
        var service = mock(FactoryAssemblyConversationService.class);
        var controller = new PlatformAssemblyInterviewController();
        ReflectionTestUtils.setField(controller, "assemblyConversations", service);
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new Errors()).build();
        mvc.perform(post(URL).contentType("application/json").content("{\"userReply\":\"面包\",\"requestId\":\"reply-62\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(URL).contentType("application/json")
                .requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE, new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER,62L,"F1","factory_super_admin"))
                .content("{\"userReply\":\"面包\",\"requestId\":\"reply-62\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
}
