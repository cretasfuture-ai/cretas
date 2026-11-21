package com.cretas.aims.controller.inventory;

import com.cretas.aims.dto.inventory.CreatePriceListRequest;
import com.cretas.aims.dto.inventory.UpdatePriceListRequest;
import com.cretas.aims.entity.inventory.PriceList;
import com.cretas.aims.entity.inventory.PriceListItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.inventory.PriceListItemRepository;
import com.cretas.aims.repository.inventory.PriceListRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 价格表更新 / 启停 / 条目增删改 (G5): 真实 JPA Context + 真实 Repository. */
@DataJpaTest(showSql = false, properties = {"spring.flyway.enabled=false"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class PriceListControllerUpdateRepositoryQueryValidationTest {

    private static final String F = "IT-PL";
    @Autowired private PriceListRepository lists;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired private PriceListItemRepository items;
    private PriceListController controller;
    private String listId;

    @BeforeEach
    void setUp() {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        controller = new PriceListController(lists, items, null);
        PriceList pl = new PriceList();
        pl.setFactoryId(F);
        pl.setName("原名");
        pl.setPriceType("PURCHASE_PRICE");
        pl.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        listId = lists.saveAndFlush(pl).getId();
    }

    private static CreatePriceListRequest.PriceItemDTO dto(String price) {
        CreatePriceListRequest.PriceItemDTO d = new CreatePriceListRequest.PriceItemDTO();
        d.setItemName("盐");
        d.setUnit("kg");
        d.setStandardPrice(new BigDecimal(price));
        return d;
    }

    @Test
    void headerUpdateAndActiveToggleAreSaved() {
        controller.updatePriceList(F, listId,
                new UpdatePriceListRequest("新名", LocalDate.of(2026, 2, 1), LocalDate.of(2026, 12, 31), "备注"));
        controller.setActive(F, listId, Map.of("active", false));
        lists.flush();

        PriceList saved = lists.findById(listId).orElseThrow();
        assertThat(saved.getName()).isEqualTo("新名");
        assertThat(saved.getEffectiveTo()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(saved.getIsActive()).isFalse();
        assertThat(saved.getPriceType()).isEqualTo("PURCHASE_PRICE"); // 类型不可改
    }

    @Test
    void invalidDateRangeAndOtherFactoryRejected() {
        assertThatThrownBy(() -> controller.updatePriceList(F, listId,
                new UpdatePriceListRequest("x", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 4, 1), null)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.setActive("OTHER", listId, Map.of("active", false)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> controller.setActive(F, listId, Map.of()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void itemAddUpdateDelete() {
        PriceListItem added = controller.addItem(F, listId, dto("10")).getData();
        items.flush();
        assertThat(items.findByPriceListId(listId)).hasSize(1);

        controller.updateItem(F, listId, added.getId(), dto("12.5"));
        items.flush();
        assertThat(items.findById(added.getId()).orElseThrow().getStandardPrice()).isEqualByComparingTo("12.5");

        controller.deleteItem(F, listId, added.getId());
        items.flush();
        assertThat(items.findByPriceListId(listId)).isEmpty();
    }

    @Test
    void itemOfAnotherListOrBadPricesRejected() {
        PriceList other = new PriceList();
        other.setFactoryId(F);
        other.setName("别的");
        other.setPriceType("PURCHASE_PRICE");
        other.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        String otherId = lists.saveAndFlush(other).getId();
        PriceListItem itm = controller.addItem(F, otherId, dto("1")).getData();

        assertThatThrownBy(() -> controller.deleteItem(F, listId, itm.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        CreatePriceListRequest.PriceItemDTO bad = dto("5");
        bad.setMinPrice(new BigDecimal("9"));
        bad.setMaxPrice(new BigDecimal("8"));
        assertThatThrownBy(() -> controller.addItem(F, listId, bad)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.addItem(F, listId, dto("-1"))).isInstanceOf(BusinessException.class);
    }
}
