package com.cretas.aims.repository.sales;

import com.cretas.aims.entity.sales.OperationalQuote;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import java.time.LocalDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan("com.cretas.aims.entity")
@EnableJpaRepositories("com.cretas.aims.repository.sales")
class CustomerQuoteScopeRepositoryQueryValidationTest {
    @Autowired EntityManager em;
    @Autowired OperationalQuoteRepository quotes;

    private void quote(String id, String factory, String customer, Long submitter, Long assignee, boolean deleted) {
        var q=new OperationalQuote(); q.setId(id); q.setQuoteNo(id); q.setFactoryId(factory);
        q.setCustomerId(customer); q.setSubmittedByUserId(submitter); q.setQuotedByUserId(assignee);
        q.setStatus("PENDING_QUOTE"); q.setCreatedAt(LocalDateTime.of(2026,10,16,0,0).plusSeconds(Integer.parseInt(id.substring(1))));
        if(deleted) q.setDeletedAt(LocalDateTime.now());
        em.persist(q); em.flush();
        // Spring auditing supplies wall-clock timestamps; fix the fixture's sort keys after persist.
        em.createNativeQuery("UPDATE operational_quotes SET created_at=:time WHERE id=:id")
                .setParameter("time",LocalDateTime.of(2026,10,16,0,0).plusSeconds(Integer.parseInt(id.substring(1))))
                .setParameter("id",id).executeUpdate();
    }
    @Test void creatorFilteringPrecedesPagingCountsAndUsesSubmitterRatherThanAssignee() {
        quote("Q1","F1","C1",3L,9L,false);
        quote("Q2","F1","C1",3L,9L,false);
        quote("Q3","F1","C1",4L,3L,false); // Assigned to self, created by someone else.
        quote("Q4","F2","C1",3L,3L,false);
        quote("Q5","F1","C2",3L,3L,false);
        quote("Q6","F1","C1",3L,3L,true);
        quote("Q7","F1","C1",null,3L,false);
        em.flush(); em.clear();
        var first=quotes.findByFactoryIdAndCustomerIdAndSubmittedByUserIdInAndDeletedAtIsNullOrderByCreatedAtDesc("F1","C1",List.of(3L),PageRequest.of(0,1));
        assertThat(first.getTotalElements()).isEqualTo(2);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.getContent()).extracting(OperationalQuote::getId).containsExactly("Q2");
        assertThat(quotes.findByFactoryIdAndCustomerIdAndSubmittedByUserIdInAndDeletedAtIsNullOrderByCreatedAtDesc("F1","C1",List.of(3L),PageRequest.of(1,1)))
                .extracting(OperationalQuote::getId).containsExactly("Q1");
        assertThat(quotes.findByFactoryIdAndCustomerIdAndSubmittedByUserIdInAndDeletedAtIsNullOrderByCreatedAtDesc("F1","C1",List.of(3L,4L),PageRequest.of(0,20)))
                .extracting(OperationalQuote::getId).containsExactly("Q3","Q2","Q1");
        assertThat(quotes.findByFactoryIdAndCustomerIdAndSubmittedByUserIdInAndDeletedAtIsNullOrderByCreatedAtDesc("F1","C1",List.of(),PageRequest.of(0,20))).isEmpty();
        assertThat(quotes.findByFactoryIdAndCustomerIdAndDeletedAtIsNullOrderByCreatedAtDesc("F1","C1",PageRequest.of(0,20)))
                .extracting(OperationalQuote::getId).containsExactly("Q7","Q3","Q2","Q1");
    }
}
