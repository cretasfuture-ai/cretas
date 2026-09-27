package com.cretas.aims.audit.activity;

import com.cretas.aims.config.RequireRoleInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ActivityEventAccessContractTest {
    private final ActivityEventRecorder recorder = mock(ActivityEventRecorder.class);
    private final ActivityEventStore store = mock(ActivityEventStore.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ActivityEventController(recorder, store))
            .addInterceptors(new RequireRoleInterceptor(new ObjectMapper()))
            .build();

    @Test
    void ordinaryRoleCannotReadAndAllowedRoleStaysWithinVerifiedFactory() throws Exception {
        mvc.perform(get("/api/mobile/F006/activity-events")
                .requestAttr("factoryId", "F006").requestAttr("userId", 42L)
                .requestAttr("role", "warehouse_worker"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/mobile/F999/activity-events")
                .requestAttr("factoryId", "F006").requestAttr("userId", 42L)
                .requestAttr("role", "factory_super_admin"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/mobile/F006/activity-events")
                .requestAttr("factoryId", "F006").requestAttr("userId", 42L)
                .requestAttr("role", "factory_super_admin"))
                .andExpect(status().isOk());
        verify(store).find(eq("F006"), isNull(), isNull(), isNull(), isNull(), isNull(), eq(100), eq(0));
    }

    @Test
    void crossFactoryCollectionIsRejectedBeforeRecording() throws Exception {
        mvc.perform(post("/api/mobile/F999/activity-events")
                .requestAttr("factoryId", "F006").requestAttr("userId", 42L)
                .contentType("application/json")
                .content("{\"events\":[]}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(recorder);
    }
}
