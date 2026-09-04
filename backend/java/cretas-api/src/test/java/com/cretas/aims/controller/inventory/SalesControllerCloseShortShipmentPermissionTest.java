package com.cretas.aims.controller.inventory;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.inventory.CloseShortShipmentRequest;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * T-SHORT-SHIP (2026-09-04 owner 拍板): 短交结案只能由 sales_manager 角色操作
 * (brief 原话 "permission: sales manager roles")。
 *
 * <p>{@code sales:read_write} 目前只授予 {@code sales_manager} 角色
 * (见 {@code PermissionServiceImpl.PERMISSION_MATRIX}, 与「取消订单」同一道闸) ——
 * 反射断言注解值, 防止日后有人不小心把它改宽成 {@code sales:read} (任何销售角色都能看,
 * 但不该都能结案) 而没人发现。
 */
class SalesControllerCloseShortShipmentPermissionTest {

    @Test
    @DisplayName("POST /orders/{orderId}/close-short-shipment 严格锁 sales:read_write")
    void closeShortShipmentIsGatedToSalesReadWrite() throws NoSuchMethodException {
        Method method = SalesController.class.getDeclaredMethod(
                "closeShortShipment",
                String.class, String.class, String.class, CloseShortShipmentRequest.class);

        RequirePermission annotation = method.getAnnotation(RequirePermission.class);

        assertNotNull(annotation, "closeShortShipment 缺 @RequirePermission —— "
                + "短交结案是不可逆的终态变更, 必须锁在 sales_manager 角色");
        assertArrayEquals(new String[]{"sales:read_write"}, annotation.value(),
                "短交结案必须严格 sales:read_write (= sales_manager), 实际: "
                        + java.util.Arrays.toString(annotation.value()));
    }
}
