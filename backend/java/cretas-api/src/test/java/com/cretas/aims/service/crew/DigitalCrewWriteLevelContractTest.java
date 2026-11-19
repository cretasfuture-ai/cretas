package com.cretas.aims.service.crew;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1816 终审 变异 A: 「写级别」= 登记的<b>每个</b>码都是写级别。逗号码是任一即可, 只要混进一个 module:read,
 * 持有读权限的人就办得了 (个人自助, 如请假提交 hr:read,hr:read_write), 数字班组不得把它当成要别人确认的写操作代办。
 */
class DigitalCrewWriteLevelContractTest {

    @Test
    void everyCodeMustBeWriteLevel() {
        assertThat(DigitalCrewService.isWriteLevel("warehouse:read_write")).isTrue();
        assertThat(DigitalCrewService.isWriteLevel("warehouse:read_write,inventory:write")).isTrue();
        assertThat(DigitalCrewService.isWriteLevel("sales:read_write, procurement:read_write")).isTrue();
    }

    @Test
    void aMixedReadAndWriteCodeIsNotWriteLevel() {
        assertThat(DigitalCrewService.isWriteLevel("hr:read,hr:read_write")).isFalse();
        assertThat(DigitalCrewService.isWriteLevel("hr:read_write,hr:read")).isFalse();
        assertThat(DigitalCrewService.hasReadLevelCode("hr:read,hr:read_write")).isTrue();
    }

    @Test
    void blankOrReadOnlyIsNotWriteLevel() {
        assertThat(DigitalCrewService.isWriteLevel(null)).isFalse();
        assertThat(DigitalCrewService.isWriteLevel(" ")).isFalse();
        assertThat(DigitalCrewService.isWriteLevel("quality:read")).isFalse();
        assertThat(DigitalCrewService.hasReadLevelCode("warehouse:read_write")).isFalse();
    }
}
