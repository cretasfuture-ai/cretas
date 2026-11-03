package com.cretas.aims.service.secrecy;

import com.cretas.aims.controller.platform.PlatformFactorySecrecyController;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.PlatformAdmin;
import com.cretas.aims.entity.enums.PlatformRole;
import com.cretas.aims.entity.secrecy.FactorySecrecySetting;
import com.cretas.aims.entity.secrecy.FactorySecrecySettingAudit;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.PlatformAdminRepository;
import com.cretas.aims.repository.secrecy.FactorySecrecySettingAuditRepository;
import com.cretas.aims.repository.secrecy.FactorySecrecySettingRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.consent.ProcessSecretSampleFilter;
import com.cretas.aims.service.platform.FactoryFeatureMenuWriteLock;
import com.cretas.aims.service.platform.PlatformFactoryControlAuthority;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 平台写保密配置 (PUT / bulk) 走真实控制器 + 真实 {@link PlatformFactoryControlAuthority} + 真实写入服务:
 * 非平台超级管理员被拒; a/b/c/e 没理由 400; 关 e 没确认 400; 每改一项一行留痕 (含「全开 / 全关」); 改完清缓存。
 */
class FactorySecrecySettingsWriteContractTest {

    static final String F = "F-SEC-W";
    static final long SUPER = 11L;
    static final long OPERATOR_ADMIN = 12L;

    final FactorySecrecySettingRepository settings = mock(FactorySecrecySettingRepository.class);
    final FactorySecrecySettingAuditRepository audits = mock(FactorySecrecySettingAuditRepository.class);
    final List<FactorySecrecySetting> rows = new ArrayList<>();
    final List<FactorySecrecySettingAudit> auditRows = new ArrayList<>();
    final ProcessSecretAccessService accessService = mock(ProcessSecretAccessService.class);
    final ProcessSecretSampleFilter sampleFilter = mock(ProcessSecretSampleFilter.class);
    FactorySecrecyConfig config;
    PlatformFactorySecrecyController controller;

    @BeforeEach
    void setUp() {
        when(settings.findByFactoryId(anyString())).thenAnswer(inv -> List.copyOf(rows));
        when(settings.save(any(FactorySecrecySetting.class))).thenAnswer(inv -> {
            FactorySecrecySetting row = inv.getArgument(0);
            rows.removeIf(r -> r.getItemCode().equals(row.getItemCode()));
            rows.add(row);
            return row;
        });
        when(audits.save(any(FactorySecrecySettingAudit.class))).thenAnswer(inv -> {
            auditRows.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(audits.findTop20ByFactoryIdOrderByChangedAtDescIdDesc(F)).thenAnswer(inv -> List.copyOf(auditRows));
        when(audits.findTop20ByFactoryIdAndItemCodeOrderByChangedAtDescIdDesc(anyString(), anyString()))
                .thenAnswer(inv -> auditRows.stream().filter(a -> a.getItemCode().equals(inv.getArgument(1))).toList());

        FactoryRepository factories = mock(FactoryRepository.class);
        Factory factory = new Factory();
        factory.setId(F);
        when(factories.findById(F)).thenReturn(Optional.of(factory));
        PlatformAdminRepository admins = mock(PlatformAdminRepository.class);
        when(admins.findById(SUPER)).thenReturn(Optional.of(admin(SUPER, PlatformRole.super_admin)));
        when(admins.findById(OPERATOR_ADMIN)).thenReturn(Optional.of(admin(OPERATOR_ADMIN, PlatformRole.operation_admin)));
        PlatformFactoryControlAuthority authority = new PlatformFactoryControlAuthority(factories, admins,
                mock(FactoryFeatureMenuWriteLock.class));
        config = new FactorySecrecyConfig(settings, (org.springframework.transaction.PlatformTransactionManager) null);
        FactorySecrecySettingsService service = new FactorySecrecySettingsService(settings, audits, authority, config,
                accessService, sampleFilter);
        controller = new PlatformFactorySecrecyController(service, authority);
    }

    // ------------------------------------------------------------------ 权限

    @Test
    void onlyAnActiveSuperAdminCanWrite() {
        assertStatus(() -> controller.put(F, "PROCESS_SECRET", on("理由"),
                request(AuthenticatedActorType.PLATFORM_ADMIN, OPERATOR_ADMIN, "PLATFORM", "platform_admin")), 403);
        assertStatus(() -> controller.put(F, "PROCESS_SECRET", on("理由"),
                request(AuthenticatedActorType.FACTORY_USER, 5L, F, "factory_super_admin")), 403);
        assertStatus(() -> controller.bulk(F, new FactorySecrecySettingsService.BulkUpdate(true, "理由", true),
                request(AuthenticatedActorType.FACTORY_USER, 5L, F, "factory_super_admin")), 403);
        MockHttpServletRequest view = superAdmin();
        view.setAttribute(com.cretas.aims.security.PlatformFactoryViewReadPolicy.REQUEST_ATTRIBUTE, true);
        assertStatus(() -> controller.put(F, "PROCESS_SECRET", on("理由"), view), 403);
        verify(settings, never()).save(any());
        verify(audits, never()).save(any());
        assertThat(controller.put(F, "PROCESS_SECRET", on("理由"), superAdmin()).getData().item().effective()).isTrue();
    }

    // ------------------------------------------------------------------ 理由 / 确认

    @Test
    void changingProcessSecretPlatformBlockEgressOrCustomerHeldNeedsAReason() {
        for (SecrecyItem item : List.of(SecrecyItem.PROCESS_SECRET, SecrecyItem.PLATFORM_RECIPE_BLOCK,
                SecrecyItem.EGRESS_REDACTION, SecrecyItem.CUSTOMER_HELD)) {
            for (String blank : new String[]{null, "", "   ", "　"}) {
                assertStatus(() -> controller.put(F, item.name(),
                        new FactorySecrecySettingsService.Update(true, null, blank, true), superAdmin()), 400);
            }
        }
        verify(settings, never()).save(any());
        // 短理由也行; d / f 不强制理由
        controller.put(F, "PROCESS_SECRET", on("客户"), superAdmin());
        controller.put(F, "TRAINING_FILTER", new FactorySecrecySettingsService.Update(true, null, null, null), superAdmin());
        controller.put(F, "AI_SECRECY_AUDIT", new FactorySecrecySettingsService.Update(false, null, null, null), superAdmin());
        assertThat(auditRows).hasSize(3);
    }

    @Test
    void turningCustomerHeldOffNeedsConfirmation() {
        controller.put(F, "CUSTOMER_HELD", on("合同约定"), superAdmin());
        assertStatus(() -> controller.put(F, "CUSTOMER_HELD",
                new FactorySecrecySettingsService.Update(false, null, "客户撤约", null), superAdmin()), 400);
        assertStatus(() -> controller.put(F, "CUSTOMER_HELD",
                new FactorySecrecySettingsService.Update(false, null, "客户撤约", false), superAdmin()), 400);
        assertThat(config.isOn(F, SecrecyItem.CUSTOMER_HELD)).isTrue();
        controller.put(F, "CUSTOMER_HELD", new FactorySecrecySettingsService.Update(false, null, "客户撤约", true), superAdmin());
        assertThat(config.isOn(F, SecrecyItem.CUSTOMER_HELD)).isFalse();
        FactorySecrecySettingAudit last = auditRows.get(auditRows.size() - 1);
        assertThat(last.getOldValue()).isEqualTo("ON");
        assertThat(last.getNewValue()).isEqualTo("OFF");
        assertThat(last.isConfirmed()).isTrue();
        assertThat(last.getReason()).isEqualTo("客户撤约");
        assertThat(last.getActorId()).isEqualTo(SUPER);
        assertThat(last.getActorRole()).isEqualTo("super_admin");
    }

    @Test
    void onlyTheAuditItemCanFollowProcessSecretAndEnabledIsRequiredOtherwise() {
        assertStatus(() -> controller.put(F, "PROCESS_SECRET",
                new FactorySecrecySettingsService.Update(null, true, "x", null), superAdmin()), 400);
        assertStatus(() -> controller.put(F, "EGRESS_REDACTION",
                new FactorySecrecySettingsService.Update(null, null, "x", null), superAdmin()), 400);
        assertStatus(() -> controller.put(F, "NOT_AN_ITEM", on("x"), superAdmin()), 400);
        // 400 的说明不回显原始 itemCode (审查 #8)
        assertThatThrownBy(() -> controller.put(F, "<script>x</script>", on("x"), superAdmin()))
                .isInstanceOf(BusinessException.class).hasMessage("未知的保密配置项");
        controller.put(F, "AI_SECRECY_AUDIT", new FactorySecrecySettingsService.Update(false, null, null, null), superAdmin());
        controller.put(F, "AI_SECRECY_AUDIT", new FactorySecrecySettingsService.Update(null, true, null, null), superAdmin());
        assertThat(rows).singleElement().satisfies(r -> assertThat(r.getEnabled()).isNull());
        assertThat(auditRows).extracting(FactorySecrecySettingAudit::getNewValue).containsExactly("OFF", "FOLLOW");
        assertThat(auditRows).extracting(FactorySecrecySettingAudit::getItemCode).containsOnly("AI_SECRECY_AUDIT");
    }

    // ------------------------------------------------------------------ 留痕

    @Test
    void everyChangedItemGetsItsOwnAuditRowAndAnUnchangedItemGetsNone() {
        controller.put(F, "PROCESS_SECRET", on("开"), superAdmin());
        controller.put(F, "PROCESS_SECRET", on("开"), superAdmin());   // 没变
        assertThat(auditRows).hasSize(1);
        assertThat(auditRows.get(0).getOldValue()).isEqualTo("UNSET");
        assertThat(auditRows.get(0).isBulk()).isFalse();
        verify(accessService, atLeastOnce()).invalidate(F);
        verify(sampleFilter, atLeastOnce()).invalidateNameCache();
    }

    @Test
    void bulkOnAndOffWriteItemByItemWithOneAuditRowEach() {
        controller.put(F, "TRAINING_FILTER", new FactorySecrecySettingsService.Update(true, null, null, null), superAdmin());
        auditRows.clear();

        controller.bulk(F, new FactorySecrecySettingsService.BulkUpdate(true, "签约", null), superAdmin());
        // d 已经开着 (没变, 不记); a b c e 各一行; f 设为跟随 a (UNSET -> FOLLOW 也是一次改动)
        assertThat(auditRows).extracting(FactorySecrecySettingAudit::getItemCode).containsExactlyInAnyOrder(
                "PROCESS_SECRET", "PLATFORM_RECIPE_BLOCK", "EGRESS_REDACTION", "CUSTOMER_HELD", "AI_SECRECY_AUDIT");
        assertThat(auditRows).allSatisfy(a -> {
            assertThat(a.isBulk()).isTrue();
            assertThat(a.getReason()).isEqualTo("签约");
        });
        for (SecrecyItem item : SecrecyItem.values()) {
            assertThat(config.isOn(F, item)).as(item.name()).isTrue();
        }

        // 全关: e 开着, 没确认不行, 也不能没理由 (一项都不写)
        int before = auditRows.size();
        assertStatus(() -> controller.bulk(F, new FactorySecrecySettingsService.BulkUpdate(false, "撤", null), superAdmin()), 400);
        assertStatus(() -> controller.bulk(F, new FactorySecrecySettingsService.BulkUpdate(false, " ", true), superAdmin()), 400);
        assertThat(auditRows).hasSize(before);
        controller.bulk(F, new FactorySecrecySettingsService.BulkUpdate(false, "撤", true), superAdmin());
        assertThat(auditRows.subList(before, auditRows.size())).extracting(FactorySecrecySettingAudit::getItemCode)
                .containsExactlyInAnyOrder("PROCESS_SECRET", "PLATFORM_RECIPE_BLOCK", "EGRESS_REDACTION",
                        "TRAINING_FILTER", "CUSTOMER_HELD");
        for (SecrecyItem item : SecrecyItem.values()) {
            assertThat(config.isOn(F, item)).as(item.name()).isFalse();
        }
        // 「全开 / 全关」本身不存值: 库里只有逐项的行
        assertThat(rows).extracting(FactorySecrecySetting::getItemCode).doesNotHaveDuplicates()
                .allMatch(code -> SecrecyItem.fromCode(code).isPresent());
    }

    @Test
    void theReadEndpointListsEveryItemAndTheRecentAudit() {
        controller.put(F, "PROCESS_SECRET", on("开"), superAdmin());
        var view = controller.list(F, superAdmin()).getData();
        assertThat(view.items()).hasSize(SecrecyItem.values().length);
        assertThat(view.items()).filteredOn(i -> i.code().equals("PLATFORM_RECIPE_BLOCK")).singleElement()
                .satisfies(i -> assertThat(i.inEffect()).isFalse());
        assertThat(view.items()).filteredOn(i -> i.code().equals("AI_SECRECY_AUDIT")).singleElement()
                .satisfies(i -> {
                    assertThat(i.stored()).isEqualTo("UNSET");
                    assertThat(i.effective()).as("跟随 a").isTrue();
                });
        assertThat(view.recentAudit()).hasSize(1);
        assertStatus(() -> controller.list(F, request(AuthenticatedActorType.FACTORY_USER, 5L, F, "factory_super_admin")), 403);
    }

    // ------------------------------------------------------------------ helpers

    static FactorySecrecySettingsService.Update on(String reason) {
        return new FactorySecrecySettingsService.Update(true, null, reason, null);
    }

    static MockHttpServletRequest superAdmin() {
        return request(AuthenticatedActorType.PLATFORM_ADMIN, SUPER, "PLATFORM", "super_admin");
    }

    static MockHttpServletRequest request(AuthenticatedActorType type, long id, String factory, String role) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, new AuthenticatedActor(type, id, factory, role));
        return request;
    }

    static PlatformAdmin admin(long id, PlatformRole role) {
        PlatformAdmin admin = PlatformAdmin.builder().platformRole(role).build();
        admin.setId(id);
        return admin;
    }

    static void assertStatus(Runnable call, int status) {
        assertThatThrownBy(call::run).isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(status));
    }
}
