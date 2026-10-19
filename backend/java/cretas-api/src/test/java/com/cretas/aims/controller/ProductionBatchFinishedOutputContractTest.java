package com.cretas.aims.controller;

import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.service.production.FormalFinishedOutputScope;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ProductionBatchFinishedOutputContractTest {
    @Test void onlyFormalOutputsReachFinishedGoodsPicker() {
        var repository = mock(ProductionBatchRepository.class);
        var scope = mock(FormalFinishedOutputScope.class);
        var controller = new ProductionBatchCostController(null,null,null,repository);
        ReflectionTestUtils.setField(controller,"formalFinishedOutputScope",scope);
        Object[] actual = {"REAL",null,"盒装",null,null,"盒",null,null};
        Object[] owner = {"OWNER",null,"目录容器",null,null,"kg",null,null};
        when(repository.findFinishedBatchSummaries(eq("F"),any(),isNull())).thenReturn(List.of(actual,owner));
        when(scope.visibleBatchNumbers("F",List.of("REAL","OWNER"))).thenReturn(Set.of("REAL"));
        assertThat(controller.listFinishedBatches("F",90,null).getData())
                .extracting(row -> row.getBatchNumber()).containsExactly("REAL");
    }
}
