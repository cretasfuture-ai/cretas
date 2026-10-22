package com.cretas.aims.service.impl;

import com.cretas.aims.entity.QualityDefect;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.DefectStatus;
import com.cretas.aims.entity.enums.DefectType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.QualityDefectRepository;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.cretas.aims.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S3-11: assignedTo must remain an optional field, but a supplied assignee must
 * be an active, non-deleted user in the requesting factory before any save.
 */
@ExtendWith(MockitoExtension.class)
class QualityDefectSourceAssigneeContractTest {

    private static final String FACTORY = "F006";
    private static final String DEFECT_ID = "defect-1";

    @Mock private QualityDefectRepository defectRepository;
    @Mock private QualityInspectionRepository inspectionRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks
    private QualityDefectServiceImpl service;

    @BeforeEach
    void wireOptionalUserRepository() {
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
    }

    @Test
    void activeSameFactoryAssigneeIsSaved() {
        QualityDefect defect = openDefect();
        User assignee = user(7L, FACTORY, true);
        when(defectRepository.findByIdAndFactoryId(DEFECT_ID, FACTORY)).thenReturn(Optional.of(defect));
        when(userRepository.findByIdAndFactoryId(7L, FACTORY)).thenReturn(Optional.of(assignee));
        when(defectRepository.save(any(QualityDefect.class))).thenAnswer(invocation -> invocation.getArgument(0));

        QualityDefect saved = service.assignDefect(FACTORY, DEFECT_ID, "返工", 7L);

        assertThat(saved.getAssignedTo()).isEqualTo(7L);
        assertThat(saved.getStatus()).isEqualTo(DefectStatus.IN_PROGRESS);
        verify(defectRepository).save(defect);
    }

    @Test
    void nullAssigneeKeepsLegacyOptionalAssignmentCompatible() {
        QualityDefect defect = openDefect();
        when(defectRepository.findByIdAndFactoryId(DEFECT_ID, FACTORY)).thenReturn(Optional.of(defect));
        when(defectRepository.save(any(QualityDefect.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.assignDefect(FACTORY, DEFECT_ID, "报废", null);

        assertThat(defect.getAssignedTo()).isNull();
        verify(userRepository, never()).findByIdAndFactoryId(any(Long.class), any(String.class));
        verify(defectRepository).save(defect);
    }

    @Test
    void foreignFactoryAssigneeIsRejectedBeforeSave() {
        QualityDefect defect = openDefect();
        when(defectRepository.findByIdAndFactoryId(DEFECT_ID, FACTORY)).thenReturn(Optional.of(defect));
        when(userRepository.findByIdAndFactoryId(7L, FACTORY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assignDefect(FACTORY, DEFECT_ID, "返工", 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于当前工厂");
        verify(defectRepository, never()).save(any(QualityDefect.class));
    }

    @Test
    void inactiveAssigneeIsRejectedBeforeSave() {
        QualityDefect defect = openDefect();
        when(defectRepository.findByIdAndFactoryId(DEFECT_ID, FACTORY)).thenReturn(Optional.of(defect));
        when(userRepository.findByIdAndFactoryId(7L, FACTORY)).thenReturn(Optional.of(user(7L, FACTORY, false)));

        assertThatThrownBy(() -> service.assignDefect(FACTORY, DEFECT_ID, "返工", 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("停用");
        verify(defectRepository, never()).save(any(QualityDefect.class));
    }

    @Test
    void softDeletedAssigneeIsRejectedBeforeSave() {
        QualityDefect defect = openDefect();
        User assignee = user(7L, FACTORY, true);
        assignee.setDeletedAt(LocalDateTime.now());
        when(defectRepository.findByIdAndFactoryId(DEFECT_ID, FACTORY)).thenReturn(Optional.of(defect));
        when(userRepository.findByIdAndFactoryId(7L, FACTORY)).thenReturn(Optional.of(assignee));

        assertThatThrownBy(() -> service.assignDefect(FACTORY, DEFECT_ID, "返工", 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("删除");
        verify(defectRepository, never()).save(any(QualityDefect.class));
    }

    private QualityDefect openDefect() {
        return QualityDefect.builder()
                .id(DEFECT_ID)
                .factoryId(FACTORY)
                .qualityInspectionId("inspection-1")
                .defectType(DefectType.OTHER)
                .quantity(BigDecimal.ONE)
                .status(DefectStatus.OPEN)
                .build();
    }

    private User user(Long id, String factoryId, boolean active) {
        User user = new User();
        user.setId(id);
        user.setFactoryId(factoryId);
        user.setIsActive(active);
        return user;
    }
}
