package com.cretas.aims.service.finance;

import com.cretas.aims.dto.finance.report.*;
import com.cretas.aims.security.PriceFieldResponseAdvice;
import org.junit.jupiter.api.Test;
import org.springframework.util.ReflectionUtils;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IncomeStatementPriceMaskingTest {
    private IncomeStatementDTO fixture() {
        var line=BalanceSheetDTO.LineItem.builder().accountCode("6001").accountName("收入").amount(BigDecimal.TEN).build();
        return IncomeStatementDTO.builder().factoryId("TEST").startYear(2026).startMonth(10).endYear(2026).endMonth(10)
                .revenues(List.of(line)).costs(List.of(line)).expenses(List.of(line)).totalRevenue(BigDecimal.TEN)
                .totalCost(BigDecimal.ONE).grossProfit(BigDecimal.TEN).totalExpense(BigDecimal.ONE)
                .operatingProfit(BigDecimal.TEN).incomeTax(BigDecimal.ONE).netProfit(BigDecimal.TEN).build();
    }
    @Test void financeReadWithoutPriceClearsAllAmountsAndNestedLines() {
        var dto=fixture();strip(dto,false);
        assertNull(dto.getTotalRevenue());assertNull(dto.getTotalCost());assertNull(dto.getGrossProfit());
        assertNull(dto.getTotalExpense());assertNull(dto.getOperatingProfit());assertNull(dto.getIncomeTax());assertNull(dto.getNetProfit());
        assertNull(dto.getRevenues());assertNull(dto.getCosts());assertNull(dto.getExpenses());
        assertEquals("TEST",dto.getFactoryId());assertEquals(10,dto.getStartMonth());
    }
    @Test void allowedPricePreservesActualMoneyAndLines() {
        var dto=fixture();strip(dto,true);assertEquals(BigDecimal.TEN,dto.getTotalRevenue());assertEquals(BigDecimal.ONE,dto.getTotalCost());
        assertEquals(BigDecimal.TEN,dto.getRevenues().get(0).getAmount());
    }
    private void strip(Object dto,boolean prices) {
        com.cretas.aims.security.PriceSensitiveContext.clear();
        var advice=new PriceFieldResponseAdvice();
        var permissions=org.mockito.Mockito.mock(com.cretas.aims.service.PermissionService.class);
        var users=org.mockito.Mockito.mock(com.cretas.aims.repository.UserRepository.class);
        org.springframework.test.util.ReflectionTestUtils.setField(advice,"permissionService",permissions);
        org.springframework.test.util.ReflectionTestUtils.setField(advice,"userRepository",users);
        var user=new com.cretas.aims.entity.User();user.setId(42L);user.setFactoryId("TEST");
        user.setRoleCode(prices?"finance_manager":"viewer");
        org.mockito.Mockito.when(users.findById(42L)).thenReturn(Optional.of(user));
        org.mockito.Mockito.when(permissions.hasPermission(user,PriceFieldResponseAdvice.PRICE_VIEW_PERMISSION)).thenReturn(prices);
        org.mockito.Mockito.when(permissions.hasPermission(user,PriceFieldResponseAdvice.FINANCE_READ_PERMISSION)).thenReturn(prices);
        var request=new org.springframework.mock.web.MockHttpServletRequest();request.setAttribute("userId",42L);
        var body=com.cretas.aims.dto.common.ApiResponse.success(dto);
        try {
            assertSame(body,advice.beforeBodyWrite(body,null,org.springframework.http.MediaType.APPLICATION_JSON,null,
                    new org.springframework.http.server.ServletServerHttpRequest(request),null));
        } finally { com.cretas.aims.security.PriceSensitiveContext.clear(); }
    }
}
