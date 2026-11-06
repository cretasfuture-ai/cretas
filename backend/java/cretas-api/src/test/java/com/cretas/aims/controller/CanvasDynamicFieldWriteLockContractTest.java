package com.cretas.aims.controller;

import com.cretas.aims.engine.DynamicFieldService;
import com.cretas.aims.engine.DynamicTableService;
import com.cretas.aims.entity.config.CanvasDynamicField;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.config.CanvasDDLLogRepository;
import com.cretas.aims.repository.config.CanvasDynamicFieldRepository;
import com.cretas.aims.service.platform.FactoryFeatureMenuWriteLock;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Dynamic-field writes must lock the factory before any row read, including type changes. */
class CanvasDynamicFieldWriteLockContractTest {
    private static final String FACTORY = "F001";
    private static final String MODULE = "inventory";
    private static final String FIELD = "lot_code";

    @Test
    void allFourDefinitionWritesAcquireLockBeforeReadAndSave() {
        CanvasDynamicFieldRepository fields = mock(CanvasDynamicFieldRepository.class);
        CanvasDDLLogRepository ddl = mock(CanvasDDLLogRepository.class);
        DynamicFieldService dynamic = mock(DynamicFieldService.class);
        DynamicTableService tables = mock(DynamicTableService.class);
        FactoryFeatureMenuWriteLock lock = mock(FactoryFeatureMenuWriteLock.class);
        DynamicFieldController controller = controller(fields, ddl, dynamic, tables, lock);

        CanvasDynamicField existing = field("TEXT", "ACTIVE");
        when(fields.findByFactoryIdAndModuleCodeAndFieldCode(FACTORY, MODULE, FIELD))
                .thenReturn(Optional.of(existing));
        when(fields.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        controller.createDynamicField(FACTORY, Map.of(
                "moduleCode", MODULE, "fieldCode", "new_field", "fieldType", "TEXT", "label", "New"));
        verifyOrder(lock, fields, "new_field", true);

        controller.updateDynamicField(FACTORY, FIELD, Map.of("moduleCode", MODULE, "label", "Updated"));
        verifyOrder(lock, fields, FIELD, true);

        controller.disableDynamicField(FACTORY, FIELD, MODULE);
        verifyOrder(lock, fields, FIELD, true);

        existing.setStatus("PENDING_DDL");
        controller.changeFieldType(FACTORY, FIELD, Map.of("moduleCode", MODULE, "newType", "NUMBER"));
        verifyOrder(lock, fields, FIELD, true);
    }

    @Test
    void duplicateCreateIsRejectedAfterLockWithoutASecondSave() {
        CanvasDynamicFieldRepository fields = mock(CanvasDynamicFieldRepository.class);
        FactoryFeatureMenuWriteLock lock = mock(FactoryFeatureMenuWriteLock.class);
        DynamicFieldController controller = controller(fields, mock(CanvasDDLLogRepository.class),
                mock(DynamicFieldService.class), mock(DynamicTableService.class), lock);
        when(fields.findByFactoryIdAndModuleCodeAndFieldCode(FACTORY, MODULE, FIELD))
                .thenReturn(Optional.of(field("TEXT", "ACTIVE")));

        assertThatThrownBy(() -> controller.createDynamicField(FACTORY, Map.of(
                "moduleCode", MODULE, "fieldCode", FIELD, "fieldType", "TEXT", "label", "Duplicate")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("actionHint", "请刷新当前模块的字段列表，编辑已有同名字段；如需新字段，请更换字段编码。")
                .hasFieldOrPropertyWithValue("hintTarget", "fieldCode")
                .hasMessageContaining("已存在");
        verify(lock).lock(FACTORY);
        verify(fields, never()).save(any());
    }

    @Test
    void unavailableLockFailsClosedBeforeAnyDefinitionRead() {
        CanvasDynamicFieldRepository fields = mock(CanvasDynamicFieldRepository.class);
        FactoryFeatureMenuWriteLock lock = mock(FactoryFeatureMenuWriteLock.class);
        doThrow(new BusinessException(503, "配置写锁不可用")).when(lock).lock(FACTORY);
        DynamicFieldController controller = controller(fields, mock(CanvasDDLLogRepository.class),
                mock(DynamicFieldService.class), mock(DynamicTableService.class), lock);

        assertThatThrownBy(() -> controller.createDynamicField(FACTORY, Map.of(
                "moduleCode", MODULE, "fieldCode", FIELD, "fieldType", "TEXT", "label", "New")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.updateDynamicField(FACTORY, FIELD,
                Map.of("moduleCode", MODULE, "label", "Updated")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.disableDynamicField(FACTORY, FIELD, MODULE))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.changeFieldType(FACTORY, FIELD,
                Map.of("moduleCode", MODULE, "newType", "NUMBER")))
                .isInstanceOf(BusinessException.class);
        verify(fields, never()).findByFactoryIdAndModuleCodeAndFieldCode(anyString(), anyString(), anyString());
        verify(fields, never()).save(any());
    }

    @Test
    void definitionEndpointsRemainTransactional() throws Exception {
        assertThat(DynamicFieldController.class.getDeclaredMethod("createDynamicField", String.class, Map.class)
                .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isTrue();
        assertThat(DynamicFieldController.class.getDeclaredMethod("updateDynamicField", String.class, String.class, Map.class)
                .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isTrue();
        assertThat(DynamicFieldController.class.getDeclaredMethod("disableDynamicField", String.class, String.class, String.class)
                .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isTrue();
        assertThat(DynamicFieldController.class.getDeclaredMethod("changeFieldType", String.class, String.class, Map.class)
                .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isTrue();
    }

    private DynamicFieldController controller(CanvasDynamicFieldRepository fields,
                                              CanvasDDLLogRepository ddl,
                                              DynamicFieldService dynamic,
                                              DynamicTableService tables,
                                              FactoryFeatureMenuWriteLock lock) {
        DynamicFieldController controller = new DynamicFieldController(fields, ddl, dynamic, tables);
        ReflectionTestUtils.setField(controller, "featureWriteLock", lock);
        return controller;
    }

    private void verifyOrder(FactoryFeatureMenuWriteLock lock, CanvasDynamicFieldRepository fields,
                             String fieldCode, boolean saved) {
        InOrder order = inOrder(lock, fields);
        order.verify(lock).lock(FACTORY);
        order.verify(fields).findByFactoryIdAndModuleCodeAndFieldCode(FACTORY, MODULE, fieldCode);
        if (saved) order.verify(fields).save(any());
    }

    private CanvasDynamicField field(String type, String status) {
        return CanvasDynamicField.builder().factoryId(FACTORY).moduleCode(MODULE).fieldCode(FIELD)
                .fieldType(type).label("Lot").status(status).config(Map.of()).build();
    }
}
