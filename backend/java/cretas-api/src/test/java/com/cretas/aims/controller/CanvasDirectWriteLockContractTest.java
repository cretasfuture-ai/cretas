package com.cretas.aims.controller;

import com.cretas.aims.entity.config.FactorySkillConfig;
import com.cretas.aims.entity.config.FactoryToolConfig;
import com.cretas.aims.entity.config.FactoryTriggerChain;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.config.FactorySkillConfigRepository;
import com.cretas.aims.repository.config.FactoryToolConfigRepository;
import com.cretas.aims.repository.config.FactoryTriggerChainRepository;
import com.cretas.aims.service.platform.FactoryFeatureMenuWriteLock;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The three legacy direct PUTs must acquire the factory write lock before they
 * read or save a configuration row.  This is intentionally a controller
 * contract: the actual lock is a mandatory-transaction Spring bean and the
 * PostgreSQL interleaving proof belongs to the integration gate.
 */
class CanvasDirectWriteLockContractTest {

    private static final String FACTORY = "F001";

    @Test
    void toolWriteLocksBeforeReadAndSave() {
        FactoryToolConfigRepository tools = mock(FactoryToolConfigRepository.class);
        FactorySkillConfigRepository skills = mock(FactorySkillConfigRepository.class);
        FactoryTriggerChainRepository chains = mock(FactoryTriggerChainRepository.class);
        FactoryFeatureMenuWriteLock lock = mock(FactoryFeatureMenuWriteLock.class);
        FactoryToolConfig existing = new FactoryToolConfig();
        when(tools.findByFactoryIdAndToolName(FACTORY, "demo.tool")).thenReturn(Optional.of(existing));
        when(tools.save(any())).thenReturn(existing);

        new TriggerChainController(tools, skills, chains, lock)
                .setToolConfig(FACTORY, "demo.tool", Map.of("enabled", true));

        InOrder order = inOrder(lock, tools);
        order.verify(lock).lock(FACTORY);
        order.verify(tools).findByFactoryIdAndToolName(FACTORY, "demo.tool");
        order.verify(tools).save(existing);
    }

    @Test
    void skillWriteLocksBeforeReadAndSave() {
        FactoryToolConfigRepository tools = mock(FactoryToolConfigRepository.class);
        FactorySkillConfigRepository skills = mock(FactorySkillConfigRepository.class);
        FactoryTriggerChainRepository chains = mock(FactoryTriggerChainRepository.class);
        FactoryFeatureMenuWriteLock lock = mock(FactoryFeatureMenuWriteLock.class);
        FactorySkillConfig existing = new FactorySkillConfig();
        when(skills.findByFactoryIdAndSkillName(FACTORY, "demo.skill")).thenReturn(Optional.of(existing));
        when(skills.save(any())).thenReturn(existing);

        new TriggerChainController(tools, skills, chains, lock)
                .setSkillConfig(FACTORY, "demo.skill", Map.of("enabled", false));

        InOrder order = inOrder(lock, skills);
        order.verify(lock).lock(FACTORY);
        order.verify(skills).findByFactoryIdAndSkillName(FACTORY, "demo.skill");
        order.verify(skills).save(existing);
    }

    @Test
    void triggerChainWriteLocksBeforeReadAndSave() {
        FactoryToolConfigRepository tools = mock(FactoryToolConfigRepository.class);
        FactorySkillConfigRepository skills = mock(FactorySkillConfigRepository.class);
        FactoryTriggerChainRepository chains = mock(FactoryTriggerChainRepository.class);
        FactoryFeatureMenuWriteLock lock = mock(FactoryFeatureMenuWriteLock.class);
        FactoryTriggerChain existing = new FactoryTriggerChain();
        existing.setFactoryId(FACTORY);
        existing.setChainCode("ORDER_CREATED");
        existing.setEnabled(false);
        when(chains.findByFactoryIdAndChainCode(FACTORY, "ORDER_CREATED")).thenReturn(Optional.of(existing));
        when(chains.save(any())).thenReturn(existing);

        FactoryTriggerChain disabledPatch = new FactoryTriggerChain();
        disabledPatch.setEnabled(false);
        new TriggerChainController(tools, skills, chains, lock)
                .setTriggerChain(FACTORY, "ORDER_CREATED", disabledPatch);

        InOrder order = inOrder(lock, chains);
        order.verify(lock).lock(FACTORY);
        order.verify(chains).findByFactoryIdAndChainCode(FACTORY, "ORDER_CREATED");
        order.verify(chains).save(existing);
    }

    @Test
    void unavailableLockFailsClosedBeforeAnyRepositoryRead() throws Exception {
        FactoryToolConfigRepository tools = mock(FactoryToolConfigRepository.class);
        FactorySkillConfigRepository skills = mock(FactorySkillConfigRepository.class);
        FactoryTriggerChainRepository chains = mock(FactoryTriggerChainRepository.class);
        FactoryFeatureMenuWriteLock lock = mock(FactoryFeatureMenuWriteLock.class);
        doThrow(new BusinessException(503, "配置写锁不可用")).when(lock).lock(FACTORY);
        TriggerChainController controller = new TriggerChainController(tools, skills, chains, lock);

        assertThrows(BusinessException.class,
                () -> controller.setToolConfig(FACTORY, "demo.tool", Map.of("enabled", true)));
        assertThrows(BusinessException.class,
                () -> controller.setSkillConfig(FACTORY, "demo.skill", Map.of("enabled", true)));
        assertThrows(BusinessException.class,
                () -> controller.setTriggerChain(FACTORY, "ORDER_CREATED", new FactoryTriggerChain()));
        verify(tools, never()).findByFactoryIdAndToolName(any(), any());
        verify(skills, never()).findByFactoryIdAndSkillName(any(), any());
        verify(chains, never()).findByFactoryIdAndChainCode(any(), any());
        assertTrue(isTransactional("setToolConfig", Map.class));
        assertTrue(isTransactional("setSkillConfig", Map.class));
        assertTrue(isTransactional("setTriggerChain", FactoryTriggerChain.class));
    }

    private boolean isTransactional(String method, Class<?> bodyType) throws NoSuchMethodException {
        return TriggerChainController.class.getDeclaredMethod(method, String.class, String.class, bodyType)
                .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class);
    }
}
