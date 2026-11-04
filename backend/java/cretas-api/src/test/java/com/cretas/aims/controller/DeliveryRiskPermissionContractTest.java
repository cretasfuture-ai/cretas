package com.cretas.aims.controller;

import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.shortage.DeliveryRiskCaseDetailRepository;
import com.cretas.aims.repository.shortage.ShortageCaseActionRepository;
import com.cretas.aims.repository.shortage.ShortageCaseEventRepository;
import com.cretas.aims.repository.shortage.ShortageCaseOutcomeCheckRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.shortage.DeliveryRiskCaseStore;
import com.cretas.aims.service.shortage.DeliveryRiskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.*;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 扫描走真实的 {@link PermissionInterceptor}: 只读角色 403 且服务一次都不被调用, 生产 / 销售写权限能扫描。 */
class DeliveryRiskPermissionContractTest {

    private final DeliveryRiskService service = mock(DeliveryRiskService.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final Set<String> granted = new HashSet<>();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new DeliveryRiskController(service, mock(DeliveryRiskCaseStore.class),
                        mock(ShortageCaseRepository.class), mock(ShortageCaseEventRepository.class),
                        mock(ShortageCaseActionRepository.class), mock(ShortageCaseOutcomeCheckRepository.class),
                        mock(DeliveryRiskCaseDetailRepository.class), mock(SalesOrderRepository.class), users, new ObjectMapper()))
                .addInterceptors(new PermissionInterceptor(permissions, users, new ObjectMapper()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        User u = new User();
        u.setId(7L);
        u.setFactoryId("F1");
        when(users.findById(7L)).thenReturn(Optional.of(u));
        when(permissions.hasAnyPermission(any(User.class), any(String[].class))).thenAnswer(i -> {
            for (Object a : Arrays.copyOfRange(i.getArguments(), 1, i.getArguments().length)) {
                if (granted.contains(a)) {
                    return true;
                }
            }
            return false;
        });
    }

    private org.springframework.test.web.servlet.ResultActions scan() throws Exception {
        return mvc.perform(post("/api/mobile/F1/delivery-risks/scan").requestAttr("userId", 7L)
                .requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE,
                        new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 7L, "F1", "dispatcher")));
    }

    @Test
    void readOnlyCannotScan() throws Exception {
        granted.add("production:read");
        scan().andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void salesWriteCanScan() throws Exception {
        granted.add("sales:read_write");
        scan().andExpect(status().isOk());
        verify(service).scan(eq("F1"), any());
    }
}
