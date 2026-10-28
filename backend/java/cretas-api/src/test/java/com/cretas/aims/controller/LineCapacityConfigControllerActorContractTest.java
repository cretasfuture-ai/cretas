package com.cretas.aims.controller;

import com.cretas.aims.entity.ProductionLineCapacityConfig;
import com.cretas.aims.entity.ProductionLineCapacityConfig.CapacityUnit;
import com.cretas.aims.entity.ProductionLineCapacityConfig.Source;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.scheduling.LineCapacityConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 写产能 / 设备时, 审计行记的必须是 JWT 验过的那个人的真实类型。
 *
 * <p>平台管理员与工厂用户的 id 是两个序列会撞号: 把平台管理员记成 FACTORY_USER, 审计行就指向了另一个工厂用户。
 */
class LineCapacityConfigControllerActorContractTest {

    private static final String CAPACITY = "/api/mobile/F006/scheduling/line-capacity/capacity";
    private static final String EQUIPMENT = "/api/mobile/F006/scheduling/line-capacity/equipment/PT-1";
    private static final String CAPACITY_BODY =
            "{\"productionLineId\":\"LINE-1\",\"capacityPerHour\":50,\"capacityUnit\":\"KG\"}";

    private LineCapacityConfigService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(LineCapacityConfigService.class);
        when(service.replaceEquipment(any(), any(), anyList(), any(), any())).thenReturn(List.of());
        when(service.setCapacity(any(), any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            ProductionLineCapacityConfig c = new ProductionLineCapacityConfig();
            c.setFactoryId("F006");
            c.setProductionLineId("LINE-1");
            c.setCapacityPerHour(new BigDecimal("50"));
            c.setCapacityUnit(CapacityUnit.KG);
            c.setSource(Source.FACTORY_CONFIG);
            c.setActor(inv.getArgument(6));
            c.setEffectiveFrom(LocalDateTime.now());
            return c;
        });
        mvc = MockMvcBuilders.standaloneSetup(new LineCapacityConfigController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void aFactoryUserOfThisFactoryIsRecordedAsAFactoryUser() throws Exception {
        mvc.perform(as(put(CAPACITY), new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 8L, "F006", "dispatcher"))
                        .contentType(MediaType.APPLICATION_JSON).content(CAPACITY_BODY))
                .andExpect(status().isOk());

        assertEquals(new ActorRef(AuthenticatedActorType.FACTORY_USER, 8L), capturedCapacityActor());
    }

    @Test
    void aPlatformAdminIsRecordedAsAPlatformAdminNotAsTheFactoryUserWithTheSameId() throws Exception {
        mvc.perform(as(put(CAPACITY), new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 8L, "PLATFORM", "super_admin"))
                        .requestAttr("userId", 8L)
                        .contentType(MediaType.APPLICATION_JSON).content(CAPACITY_BODY))
                .andExpect(status().isOk());

        assertEquals(new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN, 8L), capturedCapacityActor());
    }

    @Test
    void equipmentWritesUseTheSameActor() throws Exception {
        mvc.perform(as(put(EQUIPMENT), new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 3L, null, "platform_admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"equipmentIds\":[]}"))
                .andExpect(status().isOk());

        ArgumentCaptor<ActorRef> actor = ArgumentCaptor.forClass(ActorRef.class);
        verify(service).replaceEquipment(eq("F006"), eq("PT-1"), anyList(), eq(Source.FACTORY_CONFIG), actor.capture());
        assertEquals(new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN, 3L), actor.getValue());
    }

    @Test
    void aFactoryUserOfAnotherFactoryIsRefusedAndNothingIsWritten() throws Exception {
        mvc.perform(as(put(CAPACITY), new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 8L, "F001", "dispatcher"))
                        .contentType(MediaType.APPLICATION_JSON).content(CAPACITY_BODY))
                .andExpect(status().isForbidden());
        mvc.perform(as(put(EQUIPMENT), new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 8L, "F001", "dispatcher"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"equipmentIds\":[]}"))
                .andExpect(status().isForbidden());

        verify(service, never()).setCapacity(any(), any(), any(), any(), any(), any(), any());
        verify(service, never()).replaceEquipment(any(), any(), anyList(), any(), any());
    }

    @Test
    void aPlatformRoleWithoutFactoryBypassIsRefused() throws Exception {
        mvc.perform(as(put(CAPACITY), new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 5L, null, "auditor"))
                        .contentType(MediaType.APPLICATION_JSON).content(CAPACITY_BODY))
                .andExpect(status().isForbidden());

        verify(service, never()).setCapacity(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aBareUserIdAttributeIsNotAnIdentity() throws Exception {
        // 旧写法读的就是这个属性并一律记成 FACTORY_USER; 没有验过的身份就不写
        mvc.perform(put(CAPACITY).requestAttr("userId", 8L)
                        .contentType(MediaType.APPLICATION_JSON).content(CAPACITY_BODY))
                .andExpect(status().isUnauthorized());

        verify(service, never()).setCapacity(any(), any(), any(), any(), any(), any(), any());
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, AuthenticatedActor actor) {
        return b.requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE, actor);
    }

    private ActorRef capturedCapacityActor() {
        ArgumentCaptor<ActorRef> actor = ArgumentCaptor.forClass(ActorRef.class);
        verify(service).setCapacity(eq("F006"), eq("LINE-1"), any(), any(), eq("KG"), eq(Source.FACTORY_CONFIG), actor.capture());
        return actor.getValue();
    }
}
