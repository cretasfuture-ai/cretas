package com.cretas.aims.service.inventory.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cretas.aims.config.EnumCoercionJacksonConfig;
import com.cretas.aims.config.PriceSensitiveJacksonConfig;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.enums.TransferStatus;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.inventory.InternalTransferRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 调拨列表 / 详情回填仓库名, 以及前端读到的 JSON 形状。
 *
 * <p>#1844 终审 L-5: JSON 断言用的是<b>生产注入的</b> ObjectMapper (Boot 的 Jackson 自动配置 +
 * application.properties 的 spring.jackson.* + 本仓两个 Jackson 配置类), ⛔ 不 new ——
 * 测试 mapper 与生产不同, 序列化差异(时间格式、模块)在这里看不出来。
 */
@SpringBootTest(classes = {JacksonAutoConfiguration.class, EnumCoercionJacksonConfig.class,
        PriceSensitiveJacksonConfig.class}, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TransferListWarehouseNameContractTest {

    @Autowired ObjectMapper objectMapper;

    private static FactoryWarehouse wh(String id, String name) {
        FactoryWarehouse w = new FactoryWarehouse();
        w.setId(id);
        w.setName(name);
        return w;
    }

    @Test
    void injectedMapperIsTheProductionOne() throws Exception {
        // 仪器自检: 注入的 mapper 带本仓 EnumCoercionJacksonConfig 定制(空串枚举 → null);
        // new ObjectMapper() 在这里会抛 InvalidFormatException。
        assertThat(objectMapper.readValue("\"\"", TransferStatus.class)).isNull();
    }

    @Test
    void listFillsWarehouseNamesAndStatusLabel() throws Exception {
        InternalTransferRepository repo = mock(InternalTransferRepository.class);
        TransferServiceImpl svc = new TransferServiceImpl(repo, null, null, null, null, null, null);
        FactoryWarehouseRepository whRepo = mock(FactoryWarehouseRepository.class);
        when(whRepo.findByFactoryIdAndDeletedAtIsNullOrderByCodeAsc("F1"))
                .thenReturn(List.of(wh("W1", "主仓"), wh("W2", "生产仓")));
        ReflectionTestUtils.setField(svc, "factoryWarehouseRepository", whRepo);

        InternalTransfer t = new InternalTransfer();
        t.setSourceWarehouseId("W1");
        t.setTargetWarehouseId("W-GONE");
        t.setStatus(TransferStatus.SHIPPED);
        when(repo.findByFactoryId(eq("F1"), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(t)));

        PageResponse<InternalTransfer> page = svc.getTransfers("F1", 1, 20);
        InternalTransfer out = page.getContent().get(0);

        assertThat(out.getSourceWarehouseName()).isEqualTo("主仓");
        assertThat(out.getTargetWarehouseName()).isNull();
        var json = objectMapper.valueToTree(out);
        assertThat(json.get("statusLabel").asText()).isEqualTo("已发货");
        assertThat(json.get("sourceWarehouseName").asText()).isEqualTo("主仓");
    }

    @Test
    void detailFillsWarehouseNamesScopedToCallerFactory() throws Exception {
        InternalTransferRepository repo = mock(InternalTransferRepository.class);
        TransferServiceImpl svc = new TransferServiceImpl(repo, null, null, null, null, null, null);
        FactoryWarehouseRepository whRepo = mock(FactoryWarehouseRepository.class);
        when(whRepo.findByIdAndFactoryIdAndDeletedAtIsNull("W1", "F1")).thenReturn(Optional.of(wh("W1", "主仓")));
        // 对方工厂 / 已删除的仓库: 本厂查不到 ⇒ 名称留 null, 不拿 id 冒充。
        when(whRepo.findByIdAndFactoryIdAndDeletedAtIsNull("W-OTHER", "F1")).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(svc, "factoryWarehouseRepository", whRepo);

        InternalTransfer t = new InternalTransfer();
        t.setSourceWarehouseId("W1");
        t.setTargetWarehouseId("W-OTHER");
        t.setStatus(TransferStatus.SHIPPED);
        when(repo.findByIdAndEitherFactoryId("T1", "F1")).thenReturn(Optional.of(t));

        InternalTransfer out = svc.getTransferById("F1", "T1");

        assertThat(out.getSourceWarehouseName()).isEqualTo("主仓");
        assertThat(out.getTargetWarehouseName()).isNull();
        var json = objectMapper.valueToTree(out);
        assertThat(json.get("sourceWarehouseName").asText()).isEqualTo("主仓");
        assertThat(json.get("targetWarehouseName").isNull()).isTrue();
    }
}
