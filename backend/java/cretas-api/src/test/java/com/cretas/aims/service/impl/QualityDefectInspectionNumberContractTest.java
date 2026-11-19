package com.cretas.aims.service.impl;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.QualityDefect;
import com.cretas.aims.repository.QualityDefectRepository;
import com.cretas.aims.repository.QualityInspectionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PROTO-QC-NUMBER follow-up M1: 不良记录列表的「关联质检」显示业务编号, 由服务按<b>本厂</b>批量回填。
 * 查不到编号 (未编号 / 不属于本厂) 时留 null, 前端显示「未编号」, 不拿 UUID 顶替。
 */
@ExtendWith(MockitoExtension.class)
class QualityDefectInspectionNumberContractTest {

    private static final String FACTORY = "F006";

    @Mock private QualityDefectRepository defectRepository;
    @Mock private QualityInspectionRepository inspectionRepository;

    @InjectMocks private QualityDefectServiceImpl service;

    @Test
    @SuppressWarnings("unchecked")
    void listDefectsAttachesSameFactoryInspectionNumbers() {
        QualityDefect numbered = defect("d1", "qi-1");
        QualityDefect unnumbered = defect("d2", "qi-2");
        when(defectRepository.findByFilters(eq(FACTORY), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(numbered, unnumbered), PageRequest.of(0, 20), 2));
        when(inspectionRepository.findInspectionNumbersByFactoryIdAndIdIn(eq(FACTORY), anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{"qi-1", "QI-20261119-0003"}, new Object[]{"qi-2", null}));

        PageResponse<QualityDefect> page = service.listDefects(FACTORY, null, null, null, null, null, null, 1, 20);

        assertThat(page.getContent()).extracting(QualityDefect::getQualityInspectionNumber)
                .containsExactly("QI-20261119-0003", null);
        ArgumentCaptor<Collection<String>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(inspectionRepository).findInspectionNumbersByFactoryIdAndIdIn(eq(FACTORY), ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder("qi-1", "qi-2");
    }

    @Test
    void listByInspectionAttachesNumberToo() {
        QualityDefect d = defect("d1", "qi-1");
        when(defectRepository.findByFactoryIdAndQualityInspectionId(FACTORY, "qi-1")).thenReturn(List.of(d));
        when(inspectionRepository.findInspectionNumbersByFactoryIdAndIdIn(eq(FACTORY), anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{"qi-1", "QI-20261119-0001"}));

        assertThat(service.listByInspection(FACTORY, "qi-1"))
                .extracting(QualityDefect::getQualityInspectionNumber).containsExactly("QI-20261119-0001");
    }

    private static QualityDefect defect(String id, String inspectionId) {
        QualityDefect d = new QualityDefect();
        d.setId(id);
        d.setFactoryId(FACTORY);
        d.setQualityInspectionId(inspectionId);
        return d;
    }
}
