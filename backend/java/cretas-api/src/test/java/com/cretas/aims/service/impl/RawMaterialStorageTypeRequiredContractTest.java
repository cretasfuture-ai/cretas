package com.cretas.aims.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 储存类型「前端必填、后端不管」—— 两套规则，绕开界面就能写进空值。
 *
 * <h2>实测</h2>
 *
 * <p>2026-08-28 15:18:31，六膳门（真实客户）<b>同一秒内并行创建 21 条</b>物料
 * （2082上脑 / 和牛50 / 保水剂T22 …全是真实业务数据），{@code storage_type} <b>全空</b>。
 * 而两个界面都认为这种数据不可能存在：
 *
 * <ul>
 *   <li>web-admin {@code list.vue}: {@code if (!isPackagingMaterial && !storageType) 拦下}</li>
 *   <li>RN App {@code rawMaterialType.schema.ts}: {@code required: true} + {@code x-validator}</li>
 *   <li>后端 {@code RawMaterialTypeDTO.storageType}: <b>一个校验注解都没有</b></li>
 * </ul>
 *
 * <p>⇒ 两个真实客户端都要求它，唯一放行的是绕开它们的批量路径。
 * 所以在后端收口<b>不会打断任何真实客户端</b>，只会挡住产生脏数据的那条路。
 *
 * <h2>⛔ 为什么只拦 create，不碰 update</h2>
 *
 * <p>{@code RawMaterialTypeServiceImpl} 自己记着同形教训：
 * 「这里原本对所有包材无条件要求参考价，后果是<b>存量包材连一个字段都改不了</b>
 * —— 全平台 63 个启用包材里 59 个没有参考价」。
 *
 * <p>已经进来的那 21 条空值必须<b>还能被编辑</b>（由客户补上正确的储存类型），
 * 所以校验只加在创建路径上。
 *
 * <h2>⚠️ 代理判据，标出来</h2>
 *
 * <p>它扫源码文本确认三件事：create 里有这条校验、校验带包材豁免、update 路径没被顺手加上。
 * 已知盲区：不验证运行时行为（那需要起 Spring 上下文 + 真实数据源）。
 */
class RawMaterialStorageTypeRequiredContractTest {

    private static final Path SERVICE = Path.of(
            "src/main/java/com/cretas/aims/service/impl/RawMaterialTypeServiceImpl.java");

    private String source() throws IOException {
        assertThat(SERVICE).as("找不到被扫描的源码, 这道闸就没在守任何东西").exists();
        return Files.readString(SERVICE, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("🔴 创建非包材物料必须带储存类型")
    void createRequiresStorageTypeForNonPackaging() throws IOException {
        String src = source();

        assertThat(src)
                .as("后端不拦, 批量路径就能继续写进空值 —— 而两个界面都认为这不可能存在")
                .contains("请选择储存类型");
        assertThat(src)
                .as("必须带包材豁免: 包材本来就不需要储存类型, 一刀切会误伤")
                .contains("!isPackagingCategory(materialCategory)");
    }

    @Test
    @DisplayName("⛔ 阴性对照 · 这条校验只许出现一次 —— 加到 update 上会让存量空值改不了")
    void validationMustNotLeakIntoUpdatePath() throws IOException {
        String src = source();

        assertThat(src.split("请选择储存类型", -1).length - 1)
                .as("出现两次通常意味着 update 路径也加上了 —— "
                        + "本文件记着的教训: 对存量无条件加必填, 存量数据就一个字段都改不了")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("阳性对照: 包材豁免用的是本文件既有的判定, 不是新写一份")
    void reusesExistingPackagingPredicate() throws IOException {
        String src = source();

        assertThat(src)
                .as("isPackagingCategory 应当只有一处定义 —— 复制第二份就是「同一个东西两份」")
                .contains("private boolean isPackagingCategory(String category)");
        assertThat(src.split("private boolean isPackagingCategory", -1).length - 1).isEqualTo(1);
    }
}
