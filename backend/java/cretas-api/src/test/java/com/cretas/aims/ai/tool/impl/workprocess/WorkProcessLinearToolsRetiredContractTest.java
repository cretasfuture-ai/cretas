package com.cretas.aims.ai.tool.impl.workprocess;

import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.service.ProductWorkProcessService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 缺口 20: 写「线性工序绑定」(product_work_processes) 的 AI 入口停用 —— 画布是工艺唯一真值。
 * 判据: 预览与执行都拒绝, 且对绑定服务 / 仓库零交互(不是"返回了错误但已经写了")。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("旧线性工序绑定 AI 工具已停用")
class WorkProcessLinearToolsRetiredContractTest {

    @Mock private ProductWorkProcessRepository productWorkProcessRepository;
    @Mock private ProductWorkProcessService productWorkProcessService;
    @Mock private WorkProcessRepository workProcessRepository;
    @InjectMocks private WorkProcessConfigUpdateTool tool;

    private void call(String name) throws Exception {
        Method m = tool.getClass().getDeclaredMethod(name, String.class, Map.class, Map.class);
        m.setAccessible(true);
        try {
            m.invoke(tool, "F006", Map.of("productTypeId", "pt", "workProcessNames", java.util.List.of("卤制")), Map.of());
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @Test
    void executeRefusedWithoutTouchingBindings() {
        BusinessException e = assertThrows(BusinessException.class, () -> call("doExecute"));
        assertThat(e.getErrorCode()).isEqualTo("LEGACY_LINEAR_PROCESS_BINDING_RETIRED");
        verifyNoInteractions(productWorkProcessService, productWorkProcessRepository, workProcessRepository);
    }

    @Test
    void previewRefusedToo() {
        BusinessException e = assertThrows(BusinessException.class, () -> call("doPreview"));
        assertThat(e.getErrorCode()).isEqualTo("LEGACY_LINEAR_PROCESS_BINDING_RETIRED");
        verifyNoInteractions(productWorkProcessService, productWorkProcessRepository, workProcessRepository);
    }
}
