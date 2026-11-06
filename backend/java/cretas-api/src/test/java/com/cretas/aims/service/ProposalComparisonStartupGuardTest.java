package com.cretas.aims.service;

import com.cretas.aims.dto.decision.ProposalComparisonRequest;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

/** The service must use Spring Boot's real validation provider, not a test-only validator stub. */
class ProposalComparisonStartupGuardTest {
    @Test void bootWiresTheProviderAndRejectsStructurallyInvalidFacts() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(ProposalComparisonService.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(Validator.class).hasSingleBean(ProposalComparisonService.class);
                    var invalid = new ProposalComparisonRequest("", "", "", null, null, null, null, null, java.util.Collections.nCopies(2, null));
                    assertThatThrownBy(() -> context.getBean(ProposalComparisonService.class).compare(invalid))
                            .isInstanceOf(ConstraintViolationException.class);
                });
    }
}
