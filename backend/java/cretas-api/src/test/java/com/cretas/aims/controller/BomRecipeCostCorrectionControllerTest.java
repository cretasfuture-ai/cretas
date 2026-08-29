package com.cretas.aims.controller;

import com.cretas.aims.dto.bom.CorrectBomItemCostRequest;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.bom.BomCopyService;
import com.cretas.aims.service.bom.BomItemSubstituteService;
import com.cretas.aims.service.bom.BomRecipeService;
import com.cretas.aims.service.bom.BomSeasoningWorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BomRecipeCostCorrectionControllerTest {
    @Test
    void trustedRequestActorAndRoleReachService() {
        BomRecipeService service = mock(BomRecipeService.class);
        BomRecipeController controller = new BomRecipeController(service, mock(BomCopyService.class),
                mock(BomSeasoningWorkspaceService.class), mock(BomItemSubstituteService.class),
                mock(ProductTypeRepository.class));
        CorrectBomItemCostRequest request = new CorrectBomItemCostRequest();
        request.setReason("验收参考价更正");
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setAttribute("userId", 88L);
        http.setAttribute("role", "factory_super_admin");
        BomRecipeItem corrected = new BomRecipeItem();
        corrected.setId(680L);
        when(service.correctActivePackagingItemCost(
                "LIUSHANMEN", 680L, "验收参考价更正", 88L, "factory_super_admin"))
                .thenReturn(corrected);

        var response = controller.correctActivePackagingItemCost("LIUSHANMEN", 680L, request, http);

        assertThat(response.getData().getId()).isEqualTo(680L);
        verify(service).correctActivePackagingItemCost(
                "LIUSHANMEN", 680L, "验收参考价更正", 88L, "factory_super_admin");
    }
}
