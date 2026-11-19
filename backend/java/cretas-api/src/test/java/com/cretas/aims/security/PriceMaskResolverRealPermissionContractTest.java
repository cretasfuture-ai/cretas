package com.cretas.aims.security;

import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 成本新视图 (/cost-summary/by-product) 的价格保护靠控制器里 {@code shouldMaskPrice(authorization)} 传给
 * compute 的 maskPrice。HTTP 用例里这个解析器是 mock, 所以这里单测解析器本身, 且<b>不 mock 权限判断</b>:
 * 用真实 {@link PermissionServiceImpl} 和真实 User 岗位, 证明「无 procurement:price:view → true (脱敏)、
 * 有 → false、认不出调用人 → true (默认关闭)」。
 */
class PriceMaskResolverRealPermissionContractTest {

    private PriceMaskResolver resolverFor(String roleCode, boolean tokenResolvable) {
        MobileService mobile = mock(MobileService.class);
        UserRepository users = mock(UserRepository.class);
        if (tokenResolvable) {
            UserDTO dto = mock(UserDTO.class);
            when(dto.getId()).thenReturn(7L);
            when(mobile.getUserFromToken(anyString())).thenReturn(dto);
            User u = new User();
            u.setId(7L);
            u.setFactoryId("F-A");
            u.setRoleCode(roleCode);
            when(users.findById(7L)).thenReturn(Optional.of(u));
        } else {
            when(mobile.getUserFromToken(anyString())).thenThrow(new IllegalStateException("bad token"));
        }
        return new PriceMaskResolver(mobile, users, new PermissionServiceImpl());
    }

    @Test
    void roleWithoutPriceView_isMasked() {
        assertTrue(resolverFor("operator", true).shouldMaskPrice("Bearer t"));
        assertTrue(resolverFor("warehouse_worker", true).shouldMaskPrice("Bearer t"));
    }

    @Test
    void roleWithPriceView_isNotMasked() {
        assertFalse(resolverFor("procurement_manager", true).shouldMaskPrice("Bearer t"));
        assertFalse(resolverFor("factory_super_admin", true).shouldMaskPrice("Bearer t"));
    }

    @Test
    void unresolvableCaller_isMaskedClosedByDefault() {
        assertTrue(resolverFor("operator", false).shouldMaskPrice("Bearer t"));
        assertTrue(resolverFor("procurement_manager", true).shouldMaskPrice(null));
    }
}
