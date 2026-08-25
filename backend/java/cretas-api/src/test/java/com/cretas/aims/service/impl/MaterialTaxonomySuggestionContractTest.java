package com.cretas.aims.service.impl;

import com.cretas.aims.dto.material.MaterialSuggestDTO;
import com.cretas.aims.dto.material.MaterialTaxonomyCandidateDTO;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.material.MaterialCodeSegment;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialPackagingHierarchyRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.material.MaterialCodeSegmentRepository;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 物料分类建议 (suggestFields 的 classification* 部分) 的行为契约。
 *
 * <h2>为什么新建这个文件</h2>
 *
 * <p>{@code suggestFields} 已有测试 ({@code RawMaterialTypeCodeGenTest}), 但那些用例只覆盖
 * <b>单位 / 包装层级 / 无匹配</b> 三件事 —— {@code classificationCandidates} /
 * {@code classificationConfidence} / {@code classificationReason} 这一整块
 * <b>一条断言都没有</b> (2026-08-25 实测: 全仓 grep {@code MaterialTaxonomyCandidateDTO}
 * 在 {@code src/test} 下 0 处)。ACTIVE 台账把本项记为「分类建议目标测试未完整证明」，
 * 指的正是这一块。</p>
 *
 * <p>并且 {@code RawMaterialTypeCodeGenTest} 的类名<b>不落在 CI 的窄选择器</b>
 * ({@code *RepositoryQueryValidationTest,*StartupGuardTest,FlywayVersionUniquenessTest,*ContractTest})
 * 里 —— 它在 push 上只会被编译, 不会被执行。本类以 {@code ContractTest} 结尾, 真的会跑。</p>
 *
 * <h2>它守什么</h2>
 *
 * <p>分类建议是<b>解释性</b>的: 每个候选都要带 confidence 和一句人能读懂的 reason,
 * 让录入人知道「系统为什么建议这个分类」。守住三件事:</p>
 * <ol>
 *   <li>精确同名 → HIGH; 关键词命中 → MEDIUM (置信度不是常量)</li>
 *   <li>同一个 L3 只出一次 (多个历史物料挂同一分类时不刷屏)</li>
 *   <li>分类链断裂 (父链缺失/停用) 的历史物料<b>不出现在候选里</b>, 且不炸整个建议</li>
 * </ol>
 */
@DisplayName("物料分类建议契约 (suggestFields.classification*)")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MaterialTaxonomySuggestionContractTest {

    private static final String FACTORY_ID = "F006";

    @Mock
    private RawMaterialTypeRepository materialTypeRepository;
    @Mock
    private MaterialBatchRepository materialBatchRepository;
    @Mock
    private ConversionRepository conversionRepository;
    @Mock
    private MaterialPackagingHierarchyRepository packagingRepository;
    @Mock
    private MaterialCodeSegmentRepository materialCodeSegmentRepository;
    @Mock
    private ExcelUtil excelUtil;

    private RawMaterialTypeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RawMaterialTypeServiceImpl(
                materialTypeRepository,
                materialBatchRepository,
                conversionRepository,
                packagingRepository,
                materialCodeSegmentRepository,
                excelUtil,
                org.mockito.Mockito.mock(com.cretas.aims.service.workflow.WorkflowUnitReviewService.class));
        when(packagingRepository.findByMaterialTypeId(anyString())).thenReturn(Optional.empty());
    }

    private MaterialCodeSegment segment(Long id, short level, String label, Long parentId) {
        MaterialCodeSegment s = new MaterialCodeSegment();
        s.setId(id);
        s.setFactoryId(FACTORY_ID);
        s.setLevel(level);
        s.setSegmentLabel(label);
        s.setNormalizedLabel(label);
        s.setParentId(parentId);
        s.setIsActive(true);
        return s;
    }

    private RawMaterialType material(String id, String name, Long classificationId) {
        // RawMaterialType 没有 @Builder (只有 @Data/@AllArgsConstructor/@NoArgsConstructor),
        // 与同目录 RawMaterialTypeCodeGenTest 一致用 setter 构造。
        RawMaterialType m = new RawMaterialType();
        m.setId(id);
        m.setFactoryId(FACTORY_ID);
        m.setName(name);
        m.setCategory("原料");
        m.setUnit("kg");
        m.setCode("YL001");
        m.setCreatedBy(1L);
        m.setClassificationSegmentId(classificationId);
        m.setIsActive(true);
        return m;
    }

    /** 一条完整可用的 L1->L2->L3 链: 10 生鲜 / 20 肉禽 / 30 猪副产品。 */
    private void givenCompleteChain() {
        when(materialCodeSegmentRepository.findByIdAndFactoryId(30L, FACTORY_ID))
                .thenReturn(Optional.of(segment(30L, (short) 3, "猪副产品", 20L)));
        when(materialCodeSegmentRepository.findByIdAndFactoryId(20L, FACTORY_ID))
                .thenReturn(Optional.of(segment(20L, (short) 2, "肉禽", 10L)));
        when(materialCodeSegmentRepository.findByIdAndFactoryId(10L, FACTORY_ID))
                .thenReturn(Optional.of(segment(10L, (short) 1, "生鲜", null)));
    }

    private void givenMatches(List<RawMaterialType> matches) {
        when(materialTypeRepository.findSimilarByNameAndCategory(
                eq(FACTORY_ID), anyString(), any(), any())).thenReturn(matches);
    }

    @Nested
    @DisplayName("置信度按匹配强度分级")
    class ConfidenceGrading {

        @Test
        @DisplayName("精确同名: HIGH, 且 reason 说明是精确匹配")
        void exactNameMatchIsHighConfidence() {
            givenCompleteChain();
            givenMatches(List.of(material("m1", "猪舌", 30L)));

            MaterialSuggestDTO dto = service.suggestFields(FACTORY_ID, "猪舌", "原料");

            assertThat(dto.getClassificationConfidence()).isEqualTo("HIGH");
            assertThat(dto.getClassificationL1Id()).isEqualTo(10L);
            assertThat(dto.getClassificationL2Id()).isEqualTo(20L);
            assertThat(dto.getClassificationL3Id()).isEqualTo(30L);
            assertThat(dto.getClassificationReason()).contains("精确匹配").contains("猪舌");
        }

        /**
         * 阴性对照: 同一条链、同一个候选, <b>只改问的名字</b> —— 置信度必须从 HIGH 掉到 MEDIUM。
         * 若哪天有人把 confidence 写死成常量, 这两条里必有一条红。
         */
        @Test
        @DisplayName("关键词命中而非同名: MEDIUM, 且 reason 说明是关键词匹配")
        void keywordMatchIsMediumConfidence() {
            givenCompleteChain();
            givenMatches(List.of(material("m1", "猪舌尖", 30L)));

            MaterialSuggestDTO dto = service.suggestFields(FACTORY_ID, "猪舌", "原料");

            assertThat(dto.getClassificationConfidence()).isEqualTo("MEDIUM");
            assertThat(dto.getClassificationReason()).contains("关键词匹配");
        }

        @Test
        @DisplayName("没有任何历史物料带分类: LOW 且候选为空, 不编一个分类出来")
        void noClassifiedHistoryYieldsLowAndEmptyCandidates() {
            givenMatches(List.of(material("m1", "猪舌", null)));   // 历史物料自己就没分类

            MaterialSuggestDTO dto = service.suggestFields(FACTORY_ID, "猪舌", "原料");

            assertThat(dto.getClassificationConfidence()).isEqualTo("LOW");
            assertThat(dto.getClassificationL3Id()).isNull();
            assertThat(dto.getClassificationReason()).isNull();
            assertThat(dto.getClassificationCandidates()).isEmpty();
        }
    }

    @Nested
    @DisplayName("候选列表")
    class CandidateList {

        @Test
        @DisplayName("多个历史物料挂同一 L3: 只出一个候选 (按 L3 去重)")
        void candidatesAreDeduplicatedByL3() {
            givenCompleteChain();
            givenMatches(List.of(
                    material("m1", "猪舌", 30L),
                    material("m2", "猪舌头", 30L),
                    material("m3", "猪口条", 30L)));

            MaterialSuggestDTO dto = service.suggestFields(FACTORY_ID, "猪舌", "原料");

            assertThat(dto.getClassificationCandidates()).hasSize(1);
            assertThat(dto.getClassificationCandidates().get(0).getL3Id()).isEqualTo(30L);
        }

        @Test
        @DisplayName("不同 L3: 各出一个候选, 且首项被选作 selected")
        void distinctL3sProduceDistinctCandidates() {
            givenCompleteChain();
            when(materialCodeSegmentRepository.findByIdAndFactoryId(31L, FACTORY_ID))
                    .thenReturn(Optional.of(segment(31L, (short) 3, "牛副产品", 20L)));
            givenMatches(List.of(
                    material("m1", "猪舌", 30L),
                    material("m2", "牛舌", 31L)));

            MaterialSuggestDTO dto = service.suggestFields(FACTORY_ID, "猪舌", "原料");

            List<MaterialTaxonomyCandidateDTO> candidates = dto.getClassificationCandidates();
            assertThat(candidates).hasSize(2);
            assertThat(candidates).extracting(MaterialTaxonomyCandidateDTO::getL3Id)
                    .containsExactly(30L, 31L);
            // selected 取候选首项
            assertThat(dto.getClassificationL3Id()).isEqualTo(30L);
        }

        @Test
        @DisplayName("每个候选都带完整三级标签, 不只给 id")
        void candidatesCarryHumanReadableLabels() {
            givenCompleteChain();
            givenMatches(List.of(material("m1", "猪舌", 30L)));

            MaterialTaxonomyCandidateDTO candidate =
                    service.suggestFields(FACTORY_ID, "猪舌", "原料").getClassificationCandidates().get(0);

            assertThat(candidate.getL1Label()).isEqualTo("生鲜");
            assertThat(candidate.getL2Label()).isEqualTo("肉禽");
            assertThat(candidate.getL3Label()).isEqualTo("猪副产品");
            assertThat(candidate.getReason()).isNotBlank();
            assertThat(candidate.getConfidence()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("分类链残缺不得污染建议")
    class BrokenChainIsSkipped {

        /**
         * 这条是本文件里最实际的一条: 生产库里存在分类节点被停用/改挂的历史物料。
         * 那种物料必须<b>被跳过</b>, 而不是让整个 suggest 接口 500 ——
         * 录入人此刻正在新建物料, 建议失败不该阻断录入。
         */
        @Test
        @DisplayName("L3 节点不存在: 跳过该候选, 接口仍正常返回单位建议")
        void missingSegmentSkipsCandidateWithoutFailing() {
            when(materialCodeSegmentRepository.findByIdAndFactoryId(99L, FACTORY_ID))
                    .thenReturn(Optional.empty());
            givenMatches(List.of(material("m1", "猪舌", 99L)));

            MaterialSuggestDTO dto = service.suggestFields(FACTORY_ID, "猪舌", "原料");

            assertThat(dto.getClassificationCandidates()).isEmpty();
            assertThat(dto.getClassificationConfidence()).isEqualTo("LOW");
            // 阳性对照: 非分类部分照常给出, 证明不是整个方法挂了才导致上面为空
            assertThat(dto.getUnit()).isEqualTo("kg");
            assertThat(dto.getMatchedMaterialName()).isEqualTo("猪舌");
        }

        @Test
        @DisplayName("三级分类的父链断裂: 同样跳过, 不返回半条链")
        void brokenParentChainSkipsCandidate() {
            // L3 存在但没有 parentId -> requireValidSegmentChain 抛 BusinessException
            when(materialCodeSegmentRepository.findByIdAndFactoryId(30L, FACTORY_ID))
                    .thenReturn(Optional.of(segment(30L, (short) 3, "猪副产品", null)));
            givenMatches(List.of(material("m1", "猪舌", 30L)));

            MaterialSuggestDTO dto = service.suggestFields(FACTORY_ID, "猪舌", "原料");

            assertThat(dto.getClassificationCandidates()).isEmpty();
            assertThat(dto.getClassificationL1Id()).isNull();
            assertThat(dto.getClassificationL2Id()).isNull();
            assertThat(dto.getClassificationL3Id()).isNull();
            assertThat(dto.getUnit()).isEqualTo("kg");   // 阳性对照同上
        }

        @Test
        @DisplayName("停用的分类节点: 跳过 (停用即不该再被建议)")
        void inactiveSegmentSkipsCandidate() {
            MaterialCodeSegment inactive = segment(30L, (short) 3, "猪副产品", 20L);
            inactive.setIsActive(false);
            when(materialCodeSegmentRepository.findByIdAndFactoryId(30L, FACTORY_ID))
                    .thenReturn(Optional.of(inactive));
            givenMatches(List.of(material("m1", "猪舌", 30L)));

            MaterialSuggestDTO dto = service.suggestFields(FACTORY_ID, "猪舌", "原料");

            assertThat(dto.getClassificationCandidates()).isEmpty();
            assertThat(dto.getUnit()).isEqualTo("kg");
        }
    }

    @Nested
    @DisplayName("空输入")
    class EmptyInput {

        @Test
        @DisplayName("名称为空: 返回空 DTO, 不查库")
        void blankNameReturnsEmptyDto() {
            MaterialSuggestDTO dto = service.suggestFields(FACTORY_ID, "   ", "原料");

            assertThat(dto.getClassificationCandidates()).isNull();
            assertThat(dto.getClassificationConfidence()).isNull();
            assertThat(dto.getUnit()).isNull();
        }
    }
}
