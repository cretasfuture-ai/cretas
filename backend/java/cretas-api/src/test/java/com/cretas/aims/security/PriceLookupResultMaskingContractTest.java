package com.cretas.aims.security;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.inventory.PriceLookupResult;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

/**
 * /price-lists/lookup 的 price 必须走价格脱敏 (独立审查 高1): 无 procurement:price:view 的角色
 * 开单页拿到的 price 是 null, 有权限的照常拿到; 同时钉住 DTO 字段上的 @PriceSensitive 注解本身。
 */
@ExtendWith(MockitoExtension.class)
class PriceLookupResultMaskingContractTest {

    @Mock private PermissionService permissionService;
    @Mock private UserRepository userRepository;
    @InjectMocks private PriceFieldResponseAdvice advice;

    private ServerHttpRequest serverRequest;

    @BeforeEach
    void setup() {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setAttribute("userId", 10L);
        serverRequest = new ServletServerHttpRequest(http);
        PriceSensitiveContext.clear();
    }

    @AfterEach
    void teardown() {
        PriceSensitiveContext.clear();
    }

    private void asUser(boolean canViewPrice) {
        User user = new User();
        user.setId(10L);
        lenient().when(userRepository.findById(10L)).thenReturn(Optional.of(user));
        lenient().when(permissionService.hasPermission(eq(user), eq(PriceFieldResponseAdvice.PRICE_VIEW_PERMISSION)))
                .thenReturn(canViewPrice);
    }

    private PriceLookupResult runLookup() {
        PriceLookupResult dto = new PriceLookupResult(true, new BigDecimal("12.5000"), "CUSTOMER", "客户专属价目");
        Object out = advice.beforeBodyWrite(ApiResponse.success("查询成功", dto), null,
                MediaType.APPLICATION_JSON, null, serverRequest, null);
        assertNotNull(out);
        return dto;
    }

    @Test
    void userWithoutPriceViewGetsNullPriceButStillSeesSource() {
        asUser(false);
        PriceLookupResult dto = runLookup();
        assertNull(dto.getPrice(), "无价格查看权限: price 必须被置 null");
        assertTrue(dto.isFound());
        assertEquals("CUSTOMER", dto.getSource());
    }

    @Test
    void userWithPriceViewKeepsPrice() {
        asUser(true);
        assertEquals(0, new BigDecimal("12.5000").compareTo(runLookup().getPrice()));
    }

    @Test
    void priceFieldCarriesTheSensitiveAnnotation() throws Exception {
        Field f = PriceLookupResult.class.getDeclaredField("price");
        assertNotNull(f.getAnnotation(PriceSensitive.class));
    }

    private com.cretas.aims.dto.inventory.CustomerPriceMemoryView runMemory() {
        var dto = new com.cretas.aims.dto.inventory.CustomerPriceMemoryView(
                new BigDecimal("12.5000"), "SO-1", "id-1", "2026-11-14");
        advice.beforeBodyWrite(ApiResponse.success(dto), null, MediaType.APPLICATION_JSON, null, serverRequest, null);
        return dto;
    }

    @Test
    void priceMemoryUnitPriceIsNullForUserWithoutPriceView() {
        asUser(false);
        var dto = runMemory();
        assertNull(dto.getUnitPrice(), "最近成交价同样要按价格权限脱敏 (原 Map.of 永远剥不掉)");
        assertEquals("SO-1", dto.getSourceOrderNumber());
    }

    @Test
    void priceMemoryUnitPriceKeptForUserWithPriceView() {
        asUser(true);
        assertEquals(0, new BigDecimal("12.5000").compareTo(runMemory().getUnitPrice()));
    }

    @Test
    void priceMemoryFieldCarriesTheSensitiveAnnotation() throws Exception {
        assertNotNull(com.cretas.aims.dto.inventory.CustomerPriceMemoryView.class
                .getDeclaredField("unitPrice").getAnnotation(PriceSensitive.class));
    }
}
