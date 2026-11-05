package com.cretas.aims.service.factory;

import com.cretas.aims.dto.platform.CreateFactoryRequest;
import com.cretas.aims.repository.DroolsRuleRepository;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.service.impl.FactoryServiceImpl;
import com.cretas.aims.service.indicator.FactoryIndicatorProvisioner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RetiredRestaurantFactoryCreationContractTest {

    @Mock private FactoryRepository factoryRepository;
    @Mock private DroolsRuleRepository droolsRuleRepository;
    @Mock private FactoryIndicatorProvisioner factoryIndicatorProvisioner;

    @InjectMocks
    private FactoryServiceImpl service;

    @Test
    void restaurantIndustryCannotCreateNewFactory() {
        CreateFactoryRequest request = CreateFactoryRequest.builder()
                .name("retired restaurant")
                .industryCode("RESTAURANT")
                .regionCode("0001")
                .build();

        assertThatThrownBy(() -> service.createFactory(request))
                .hasMessageContaining("餐饮业务类型已退役");
        verify(factoryRepository, never()).save(any());
    }
}
