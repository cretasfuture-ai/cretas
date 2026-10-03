package com.cretas.aims.service.impl;

import com.cretas.aims.entity.conversation.ConversationMemory;
import com.cretas.aims.repository.conversation.ConversationMemoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ConversationMemoryOwnershipContractTest {

    @ParameterizedTest
    @CsvSource(value={"NULL,77", "'',77", "' ',77", "F006,NULL"}, nullValues="NULL")
    void incompletePrincipalNeverReadsOrWritesMemory(String factoryId, Long userId) {
        var repository = mock(ConversationMemoryRepository.class);
        var service = new ConversationMemoryServiceImpl(repository, null);
        assertThat(service.getOrCreateContext(factoryId, userId, "server-session")).isNull();
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @CsvSource(value={"F007,77", "F006,78", "NULL,77", "F006,NULL"}, nullValues="NULL")
    void foreignOrIncompleteStoredOwnerIsNotTouchedOrSaved(String factoryId, Long userId) {
        var repository = mock(ConversationMemoryRepository.class);
        var memory = spy(ConversationMemory.builder().sessionId("server-session")
                .factoryId(factoryId).userId(userId).build());
        when(repository.findBySessionId("server-session")).thenReturn(Optional.of(memory));
        var service = new ConversationMemoryServiceImpl(repository, null);
        assertThat(service.getOrCreateContext("F006", 77L, "server-session")).isNull();
        verify(memory, never()).touch();
        verify(repository, never()).save(any());
        verify(repository).findBySessionId("server-session");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void legitimateOwnerReceivesSameServerSessionAndRefreshesActivity() {
        var repository = mock(ConversationMemoryRepository.class);
        var memory = spy(ConversationMemory.builder().sessionId("server-session")
                .factoryId("F006").userId(77L).build());
        when(repository.findBySessionId("server-session")).thenReturn(Optional.of(memory));
        var context = new ConversationMemoryServiceImpl(repository, null)
                .getOrCreateContext("F006", 77L, "server-session");
        assertThat(context.getSessionId()).isEqualTo("server-session");
        assertThat(context.getFactoryId()).isEqualTo("F006");
        assertThat(context.getUserId()).isEqualTo(77L);
        verify(memory).touch();
        verify(repository).save(memory);
    }
}
