package com.cretas.aims.ai.tool.impl.canvas;

import com.cretas.aims.entity.config.FactoryTriggerChain;
import com.cretas.aims.repository.config.FactoryTriggerChainRepository;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Canvas can switch a trigger chain (or a step) on without the trigger-chain PUT and its
 * run-as / permission checks, so switching anything on here clears the run-as user: the chain is
 * refused (RUN_AS_MISSING) until a factory user saves it through the PUT. Switching off keeps it.
 */
class CanvasTriggerChainRunAsContractTest {
    private final FactoryTriggerChainRepository repo = mock(FactoryTriggerChainRepository.class);
    private final CanvasUpdateTriggerChainTool tool = new CanvasUpdateTriggerChainTool();
    private FactoryTriggerChain chain;

    @BeforeEach void setup() throws Exception {
        Field field = CanvasUpdateTriggerChainTool.class.getDeclaredField("factoryTriggerChainRepo");
        field.setAccessible(true);
        field.set(tool, repo);
        chain = new FactoryTriggerChain();
        chain.setFactoryId("F001");
        chain.setChainCode("C");
        chain.setEnabled(false);
        chain.setRunAsUserId(42L);
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(new HashMap<>(Map.of("order", 1, "tool", "material_expired_query", "enabled", false)));
        chain.setSteps(steps);
        when(repo.findByFactoryIdAndChainCode("F001", "C")).thenReturn(Optional.of(chain));
    }

    @Test void enablingTheChainOnCanvasClearsTheStoredRunAsUser() throws Exception {
        Map<String, Object> result = tool.doExecute("F001", Map.of("chainCode", "C", "enabled", true), Map.of());
        assertThat(chain.getEnabled()).isTrue();
        assertThat(chain.getRunAsUserId()).isNull();
        assertThat(String.valueOf(result)).contains("触发链配置");
        verify(repo).save(chain);
    }

    @Test void enablingAStepOnCanvasClearsItTooButDisablingKeepsIt() throws Exception {
        tool.doExecute("F001", Map.of("chainCode", "C", "stepOrder", 1, "stepEnabled", true), Map.of());
        assertThat(chain.getRunAsUserId()).isNull();

        chain.setRunAsUserId(42L);
        tool.doExecute("F001", Map.of("chainCode", "C", "enabled", false), Map.of());
        assertThat(chain.getRunAsUserId()).isEqualTo(42L);
    }
}
