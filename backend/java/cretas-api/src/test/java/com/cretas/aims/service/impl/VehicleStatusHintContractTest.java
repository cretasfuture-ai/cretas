package com.cretas.aims.service.impl;

import com.cretas.aims.entity.Vehicle;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.VehicleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 车辆状态清单：<b>只许有一份，且那一份必须是枚举自己</b>。
 *
 * <h2>被守的行为</h2>
 *
 * <p>用户把车辆状态填错时，后端回一句「请使用合法的状态值: …」。那句话必须
 * <b>逐字等于</b> {@link Vehicle.VehicleStatus} 的取值集合 —— 因为用户会照着它去填。
 *
 * <p>2026-08-19 之前它是手写的，而且<b>两个方向同时错</b>：
 * <ul>
 *   <li>枚举里有的 {@code dispatched} 清单里<b>没有</b> —— 界面拿不到中文映射，直接显示英文码；
 *       prod 上真有车处在这个状态（2026-08-19 实测 {@code public.vehicles} 2 行：
 *       dispatched 1 / available 1，⇒ 这不是理论风险）；</li>
 *   <li>清单里的 {@code in_use} / {@code retired} 枚举里<b>根本没有</b> ——
 *       用户照着报错填，{@code VehicleStatus.valueOf()} 抛 → <b>再吃一个 400</b>。</li>
 * </ul>
 *
 * <p>那份错清单还是<b>源头</b>：web-admin 车辆字典的 statusMap 曾逐字照抄它
 * （见 {@code web-admin/src/views/sales/vehicles/list.vue} 的注释）。⇒ 形态 D。
 *
 * <h2>这道闸的两层</h2>
 *
 * <ol>
 *   <li><b>能抽成一份的抽成一份</b>：报错文案由 {@code legalValuesHint()} 生成。
 *       本类断言的是<b>行为</b>（真的调一次 {@code updateVehicleStatus}），不是源码文本 ——
 *       所以不存在「闸命中自己的说明注释」那个坑。</li>
 *   <li><b>抽不成一份的钉住两份一致</b>（形态 D′）：{@code VehicleController} 上那份
 *       Swagger {@code @Parameter(description = …)} 是注解值，必须是编译期常量，
 *       <b>生成不了</b>。所以这里改成扫它的源码、断言它与枚举一致。</li>
 * </ol>
 *
 * <p>⚠️ 第 2 层会读 {@code src/main} 源码 ⇒ 按
 * {@code GateSelectorCoverageContractTest} 的要求，类名必须以 {@code ContractTest} 结尾
 * 才会被 CI 选择器执行。
 *
 * <h2>范围（形态 E：宁可窄而可信）</h2>
 *
 * <p>全仓私有状态表约 72 份，本闸<b>只</b>守车辆这一份。其余已在交付说明里登记，未纳入。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("车辆状态清单：报错文案由枚举生成，且 Swagger 那份与枚举一致")
class VehicleStatusHintContractTest {

    @Mock
    private VehicleRepository vehicleRepository;

    @InjectMocks
    private VehicleServiceImpl vehicleService;

    /** 已知<b>不在</b>枚举里的取值 —— 它们一旦出现在任何面向用户的清单里就是缺陷。 */
    private static final List<String> NEVER_LEGAL = List.of("in_use", "retired");

    private static final Path CONTROLLER_SOURCE = Paths.get(
            "src/main/java/com/cretas/aims/controller/VehicleController.java");

    // ---------------------------------------------------------------- 仪器自检

    @Test
    @DisplayName("阳性对照：枚举真的读得出取值，且 NEVER_LEGAL 真的不在里面")
    void positiveControl() {
        List<String> names = Arrays.stream(Vehicle.VehicleStatus.values()).map(Enum::name).toList();

        // 读空了的话后面每一条「不包含 X」都会变成恒真式。
        assertThat(names).as("VehicleStatus 读出空列表 —— 仪器坏了, 后面的断言全部无意义").isNotEmpty();
        assertThat(names).as("dispatched 是本缺陷的主角, 它必须在枚举里").contains("dispatched");

        // NEVER_LEGAL 的前提: 它们确实不是合法值。前提不成立的话「清单里不许有它们」就没意义。
        assertThat(names)
                .as("in_use / retired 居然进枚举了 —— 那本测试的整个前提都要重写")
                .doesNotContainAnyElementsOf(NEVER_LEGAL);
    }

    // -------------------------------------------- 第 1 层：报错文案 == 枚举（行为断言）

    /** 真的走一次产品入口，拿到用户会看到的那句话。 */
    private String hintFromRealEntryPoint() {
        Vehicle vehicle = new Vehicle();
        vehicle.setId("v-1");
        vehicle.setFactoryId("F001");
        when(vehicleRepository.findByIdAndFactoryId(anyString(), anyString()))
                .thenReturn(Optional.of(vehicle));

        BusinessException thrown = (BusinessException) org.assertj.core.api.Assertions
                .catchThrowable(() -> vehicleService.updateVehicleStatus("F001", "v-1", "in_use"));

        assertThat(thrown).as("填一个非法状态居然没抛 —— 闸没量到东西").isNotNull();
        assertThat(thrown.getActionHint()).as("报错没有 hint —— 用户看不到任何可填的取值").isNotNull();
        return thrown.getActionHint();
    }

    @Test
    @DisplayName("🔴 报错里列的取值 == 枚举的取值（双向，一个不多一个不少）")
    void hintListsExactlyTheEnumValues() {
        String hint = hintFromRealEntryPoint();

        // 方向 ①：枚举有、清单没有 → 用户不知道这个状态存在, 界面还会显示英文原码。
        for (Vehicle.VehicleStatus status : Vehicle.VehicleStatus.values()) {
            assertThat(hint)
                    .as("枚举里有 %s 但报错清单里没列 —— 用户不知道可以填它", status.name())
                    .contains(status.name());
        }

        // 方向 ②：清单有、枚举没有 → 用户照着填, valueOf() 抛, 再吃一个 400。
        for (String bogus : NEVER_LEGAL) {
            assertThat(hint)
                    .as("报错清单列了 %s, 而枚举里根本没有 —— 照着填会再拿一个 400", bogus)
                    .doesNotContain(bogus);
        }
    }

    @Test
    @DisplayName("🔴 清单里的每一个取值都必须真能通过 valueOf（照着填不许再报错）")
    void everyAdvertisedValueIsActuallyAccepted() {
        String hint = hintFromRealEntryPoint();

        // 从用户看到的那句话里切出取值, 逐个真的喂给 valueOf。
        // 这一条守的不是「文案长什么样」, 是「照着文案做能不能成」。
        String listed = hint.substring(hint.indexOf(':') + 1);
        List<String> advertised = Arrays.stream(listed.split("/"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        assertThat(advertised)
                .as("从 hint 里一个取值都没切出来 —— 分隔符变了, 本条断言已失效")
                .hasSameSizeAs(Arrays.asList(Vehicle.VehicleStatus.values()));

        for (String value : advertised) {
            assertThat(Vehicle.VehicleStatus.valueOf(value))
                    .as("报错让用户填 %s, 但 valueOf 不认它", value)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("报错文案不许再写死取值 —— 只能由枚举生成")
    void hintIsDerivedNotHandWritten() {
        // 被守的行为: 往枚举里加一个值, 报错文案必须自己跟着变。
        // 直接断言「生成器的产物 == 文案里的那一段」, 而不是断言源码里没有某个字符串
        // (那种写法会命中自己的说明注释 —— 本仓今天已有两条线被咬到)。
        assertThat(hintFromRealEntryPoint())
                .as("报错文案与 legalValuesHint() 对不上 —— 说明它是另写的一份")
                .contains(Vehicle.VehicleStatus.legalValuesHint());
    }

    // ------------------------- 第 2 层：抽不掉的那一份（Swagger 注解）钉住「两份一致」

    @Test
    @DisplayName("🔴 Swagger @Parameter 里那份手写清单必须与枚举一致（注解值抽不成一份）")
    void swaggerParameterDescriptionMatchesTheEnum() throws IOException {
        assertThat(Files.exists(CONTROLLER_SOURCE))
                .as("找不到 %s —— 文件被移动或改名了, 请同步本测试", CONTROLLER_SOURCE)
                .isTrue();

        String source = Files.readString(CONTROLLER_SOURCE, StandardCharsets.UTF_8);

        // ⚠️ 剥掉注释再扫 —— 否则闸会命中说明性注释里引用的坏清单, 长成假阳/假阴。
        String code = stripComments(source);

        int at = code.indexOf("车辆状态筛选");
        assertThat(at).as("找不到那条 @Parameter 描述 —— 闸没量到东西, 请同步本测试").isGreaterThan(-1);
        String description = code.substring(at, code.indexOf(')', at));

        for (Vehicle.VehicleStatus status : Vehicle.VehicleStatus.values()) {
            assertThat(description)
                    .as("Swagger 描述漏了枚举值 %s", status.name())
                    .contains(status.name());
        }
        for (String bogus : NEVER_LEGAL) {
            assertThat(description)
                    .as("Swagger 描述列了枚举里没有的 %s", bogus)
                    .doesNotContain(bogus);
        }
    }

    @Test
    @DisplayName("仪器自检：剥注释真的起作用了（否则上一条会命中说明性注释）")
    void stripCommentsActuallyStrips() {
        String sample = """
                // 坏清单: available / loading / in_use / maintenance / retired
                /* 同样提到 retired */
                String real = "available";
                """;
        String stripped = stripComments(sample);
        assertThat(stripped).as("行注释没被剥掉").doesNotContain("坏清单");
        assertThat(stripped).as("块注释没被剥掉").doesNotContain("同样提到");
        assertThat(stripped).as("把代码也剥掉了 —— 剥过头了").contains("String real");
    }

    /** 去掉 {@code //} 行注释与 {@code /*} 块注释（含 javadoc）。字符串字面量里的 // 极少，够用。 */
    private static String stripComments(String source) {
        return source
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//[^\\n]*", "");
    }
}
