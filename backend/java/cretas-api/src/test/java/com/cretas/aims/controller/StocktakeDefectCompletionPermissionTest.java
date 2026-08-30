package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class StocktakeDefectCompletionPermissionTest {
    @Test void completionRequiresWarehouseReadWrite() throws Exception {
        var method = StocktakeController.class.getMethod("resolvePendingDefectCount", String.class,
                String.class, com.cretas.aims.dto.factory.StocktakeDefectCountRequest.class,
                jakarta.servlet.http.HttpServletRequest.class);
        assertArrayEquals(new String[]{"warehouse:read_write"},
                method.getAnnotation(RequirePermission.class).value());
    }
}
