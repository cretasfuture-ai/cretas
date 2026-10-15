package com.cretas.aims.blueprint.interview;

import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest.TypeItem;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssemblyMaterialStorageContractTest {
    private final RawMaterialTypeRepository materials = mock(RawMaterialTypeRepository.class);
    private final FactoryBuildApplyValidator validator = new FactoryBuildApplyValidator();
    private TypeItem material(String storage) {
        ReflectionTestUtils.setField(validator, "rawMaterialTypeRepository", materials);
        var item = new TypeItem(); item.setCode("FLOUR"); item.setName("面粉"); item.setCategory("原料"); item.setUnit("kg"); item.setStorageType(storage); return item;
    }

    @Test void missingStorageIsBlockedDuringPlanningInsteadOfDuringFormalWrite() {
        var problems = new ArrayList<String>();
        validator.checkMaterialStorage(problems, "F1", List.of(material(null)));
        assertThat(problems).singleElement().asString().contains("面粉", "储存类型", "重新保存");
        verify(materials, never()).save(any());
    }

    @Test void explicitNormalAndDryAreAcceptedWithoutChangingTheirValues() {
        for (String code : List.of("normal", "dry")) {
            var item = material(code); var problems = new ArrayList<String>();
            validator.checkMaterialStorage(problems, "F1", List.of(item));
            assertThat(problems).isEmpty(); assertThat(item.getStorageType()).isEqualTo(code);
        }
    }

    @Test void packagingOverwriteCannotRetainOrIntroduceAStorageType() {
        for (String category : List.of("包材", "包辅材", "PACKAGING", "PACKAGE")) {
            var existing = new RawMaterialType(); existing.setCategory(category); existing.setStorageType("normal");
            var request = material("dry"); request.setCategory(category);
            FactoryBuildApplyService.applyMaterialStorage(existing, request);
            assertThat(existing.getStorageType()).isNull();
            assertThat(existing.getMainCategory()).isEqualTo("包材");
            var problems = new ArrayList<String>();
            validator.checkMaterialStorage(problems, "F1", List.of(request));
            assertThat(problems).singleElement().asString().contains("不适用储存类型");
        }
    }

    @Test void invalidStorageIsRejectedButPackagingAndExistingRecordsKeepTheirContract() {
        var problems = new ArrayList<String>();
        validator.checkMaterialStorage(problems, "F1", List.of(material("guessed")));
        assertThat(problems).singleElement().asString().contains("储存类型无效");
        var packaging = material(null); packaging.setCategory("包材"); problems.clear();
        validator.checkMaterialStorage(problems, "F1", List.of(packaging)); assertThat(problems).isEmpty();
        packaging.setStorageType("dry");
        validator.checkMaterialStorage(problems, "F1", List.of(packaging));
        assertThat(problems).singleElement().asString().contains("包材", "不适用储存类型");
        problems.clear();
        when(materials.findByFactoryIdAndCode("F1", "FLOUR")).thenReturn(Optional.of(new RawMaterialType()));
        validator.checkMaterialStorage(problems, "F1", List.of(material(null))); assertThat(problems).isEmpty();
    }
}
