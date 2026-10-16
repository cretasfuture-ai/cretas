package com.cretas.aims.security;

import com.cretas.aims.entity.enums.DataScope;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.impl.MaterialBatchServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InventoryTypedScopeContractTest {
    @AfterEach void clear(){RequestContextHolder.resetRequestAttributes();}
    @ParameterizedTest @ValueSource(longs={56,900056})
    void platformInventoryScopeNeverLooksUpCollidingOrMissingFactoryUser(long id) {
        var service=mock(MaterialBatchServiceImpl.class,CALLS_REAL_METHODS);
        var users=mock(UserRepository.class);var permissions=mock(PermissionService.class);var resolver=mock(DataScopeResolver.class);
        ReflectionTestUtils.setField(service,"userRepository",users);ReflectionTestUtils.setField(service,"permissionService",permissions);ReflectionTestUtils.setField(service,"dataScopeResolver",resolver);
        var request=new MockHttpServletRequest();request.setAttribute("userId",id);
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN,id,"PLATFORM","super_admin"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        DataScopeContext scope=ReflectionTestUtils.invokeMethod(service,"inventoryScope","F2");
        assertThat(scope.getScope()).isEqualTo(DataScope.ALL);assertThat(scope.getFactoryId()).isEqualTo("F2");
        ReflectionTestUtils.invokeMethod(service,"requireInventoryReadPermission","F2");
        ReflectionTestUtils.invokeMethod(service,"requireProductionSelectionPermission","F2");
        verifyNoInteractions(users,permissions,resolver);
    }
    @ParameterizedTest @ValueSource(strings={"factory","platform_auditor"})
    void wrongFactoryOrUnprivilegedPlatformCannotObtainInventoryScope(String kind) {
        var service=mock(MaterialBatchServiceImpl.class,CALLS_REAL_METHODS);var users=mock(UserRepository.class);
        ReflectionTestUtils.setField(service,"userRepository",users);
        var actor=kind.equals("factory")?new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER,56L,"F1","platform_admin"):
                new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN,56L,"PLATFORM","auditor");
        var request=new MockHttpServletRequest();request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,actor);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertThatThrownBy(()->ReflectionTestUtils.invokeMethod(service,"inventoryScope","F2"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(users);
    }
}
