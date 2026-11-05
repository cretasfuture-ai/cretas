package com.cretas.aims.ai.catalog;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.impl.camera.CameraSubscribeTool;
import com.cretas.aims.ai.tool.impl.camera.CameraUnsubscribeTool;
import com.cretas.aims.ai.tool.impl.sales.ShipmentConfirmCreateTool;
import com.cretas.aims.ai.tool.impl.sales.ShipmentPendingTodayTool;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.isapi.IsapiDevice;
import com.cretas.aims.service.inventory.SalesService;
import com.cretas.aims.service.isapi.IsapiDeviceService;
import com.cretas.aims.service.isapi.IsapiEventSubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FactoryCatalogReadWriteBoundaryContractTest {
    @Test
    void sampleDisposalQueryAndPasswordNavigationAreNotMistakenForWrites() {
        var guard = new com.cretas.aims.ai.tool.WriteGuardService();
        assertThat(guard.isWriteTool(new com.cretas.aims.ai.tool.impl.foodsafety.FoodSampleQueryPendingDisposeTool())).isFalse();
        assertThat(guard.isWriteTool(new com.cretas.aims.ai.tool.impl.system.PasswordResetTool())).isFalse();
        var intent = FactoryIntentCatalog.project(com.cretas.aims.entity.config.AIIntentConfig.builder()
                .intentCode("SYSTEM_PASSWORD_RESET").sensitivityLevel("HIGH").requiresApproval(true).build());
        assertThat(guard.isWriteIntent(intent)).isFalse();
        assertThat(intent.getRequiresApproval()).isFalse();
    }
    @Test
    void pendingShipmentIgnoresWriteParametersAndUsesOnlyScopedReadService() {
        SalesService sales = mock(SalesService.class);
        when(sales.getSalesOrdersFiltered("A", null, null, null, 1, 200))
                .thenReturn(PageResponse.of(List.of(), 1, 200, 0L));
        var mixed = new ShipmentConfirmCreateTool();
        ReflectionTestUtils.setField(mixed, "salesService", sales);
        var read = new ShipmentPendingTodayTool();
        ReflectionTestUtils.setField(read, "shipments", mixed);
        Map<String,Object> result = ReflectionTestUtils.invokeMethod(read, "doExecute", "A",
                Map.of("salesOrderId", "injected", "items", List.of(Map.of("actualQty", 100))), Map.of());
        assertThat(result).containsEntry("mode", "QUERY").containsEntry("orderCount", 0);
        verify(sales).getSalesOrdersFiltered("A", null, null, null, 1, 200);
        verifyNoMoreInteractions(sales);
        assertThat(read.getAccessMode()).isEqualTo(ToolExecutor.AccessMode.READ);
        assertThat(read.getRequiredPermissions()).contains("sales:read");
        assertThat(read.getAlsoRequiredPermissions()).containsExactly("sales_order");
    }

    @Test
    void bothCameraPreviewsReadOnlyOwnFactoryWithoutChangingSubscriptions() {
        var devices = mock(IsapiDeviceService.class);
        var subscriptions = mock(IsapiEventSubscriptionService.class);
        var device = new IsapiDevice();
        device.setId("camera-A");
        device.setDeviceName("A camera");
        when(devices.getDevice("A", "camera-A")).thenReturn(device);
        when(subscriptions.getActiveSubscriptionIds("A")).thenReturn(List.of("camera-A"));
        for (Object tool : List.of(new CameraSubscribeTool(), new CameraUnsubscribeTool())) {
            ReflectionTestUtils.setField(tool, "deviceService", devices);
            ReflectionTestUtils.setField(tool, "subscriptionService", subscriptions);
            Map<String,Object> result = ReflectionTestUtils.invokeMethod(tool, "doPreview", "A",
                    Map.of("deviceId", "camera-A"), Map.of());
            assertThat(result).containsEntry("status", "PREVIEW").containsEntry("deviceId", "camera-A");
            var executor = (ToolExecutor) tool;
            assertThat(executor.getAccessMode()).isEqualTo(ToolExecutor.AccessMode.WRITE);
            assertThat(executor.supportsPreview()).isTrue();
            assertThat(executor.hasPermission("operator")).isFalse();
        }
        verify(subscriptions, times(2)).getActiveSubscriptionIds("A");
        verifyNoMoreInteractions(subscriptions);
        verify(devices, times(2)).getDevice("A", "camera-A");
        verifyNoMoreInteractions(devices);
    }
}
