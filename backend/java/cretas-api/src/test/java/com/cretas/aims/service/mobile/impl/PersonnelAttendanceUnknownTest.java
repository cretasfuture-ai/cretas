package com.cretas.aims.service.mobile.impl;

import com.cretas.aims.dto.MobileDTO;
import com.cretas.aims.entity.TimeClockRecord;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.TimeClockRecordRepository;
import com.cretas.aims.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 「没有打卡记录」≠「所有人都缺勤」。
 *
 * <h3>它守的那次事故（2026-08-29 逐屏走查实拍）</h3>
 * 人员报表（F006，本月）:
 * <pre>
 *   总人数 23    在岗 0    缺勤 23    平均出勤率 0.0%
 *   工时排行榜（TOP 10）：暂无工时数据
 * </pre>
 *
 * <p>查库：{@code time_clock_records} <b>全表零行</b> —— 所有工厂都没用过打卡功能。
 * 也就是说这张报表对<b>每一个客户</b>都断言「你全厂的人全部缺勤」。
 *
 * <p>⛔ 这是把「我不知道」翻译成了一个很具体的<b>指控</b>，比翻译成 0 更糟：
 * 一个 0 还能被读成「暂无数据」，「缺勤 23 人」读起来是管理层要去追责的事实。
 *
 * <h3>分界线</h3>
 * 判据是<b>这个窗口里有没有任何打卡记录</b>，⛔ 不是「某个人有没有」——
 * 有人打卡而某人没打，那才是真的缺勤，照常算。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PersonnelAttendanceUnknownTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private TimeClockRecordRepository timeClockRecordRepository;

    @InjectMocks
    private MobileBusinessServiceImpl service;

    private static User user(long id, String department) {
        User u = new User();
        u.setId(id);
        u.setDepartment(department);
        return u;
    }

    private static TimeClockRecord clock(long userId, int minutes) {
        TimeClockRecord r = new TimeClockRecord();
        r.setUserId(userId);
        r.setWorkDurationMinutes(minutes);
        return r;
    }

    private void givenUsers(List<User> users) {
        when(userRepository.findByFactoryId(anyString())).thenReturn(users);
        when(userRepository.countExpiringContracts(anyString(), any())).thenReturn(0L);
    }

    private void givenClockRecords(List<TimeClockRecord> records) {
        when(timeClockRecordRepository.findByFactoryIdAndUserIdInAndClockDateBetween(
                anyString(), anyList(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(records);
    }

    @Test
    @DisplayName("🔴 一条打卡记录都没有: 在岗/缺勤/出勤率/工时 一律 null —— ⛔ 不许说「全员缺勤」")
    void noClockRecordsMeansUnknownNotAllAbsent() {
        givenUsers(List.of(user(1, "生产部"), user(2, "生产部"), user(3, "品控部")));
        givenClockRecords(new ArrayList<>());

        MobileDTO.PersonnelStatistics stats =
                service.getPersonnelStatistics("F006", "2026-08-01", "2026-08-29");

        // 人数是知道的 —— 它来自 users 表, 与打卡无关
        assertThat(stats.getTotalEmployees()).isEqualTo(3);
        assertThat(stats.getActiveDepartments()).isEqualTo(2);

        // 出勤相关的一律「算不出」
        assertThat(stats.getTotalPresent()).as("没有打卡记录时不许报在岗人数").isNull();
        assertThat(stats.getTotalAbsent())
                .as("这就是事故本身: 3 个人被判成 3 个缺勤")
                .isNull();
        assertThat(stats.getAvgAttendanceRate()).as("0.0% 是编出来的").isNull();
        assertThat(stats.getTotalWorkHours()).as("0 小时同样是编出来的").isNull();
        assertThat(stats.getAvgWorkHoursPerEmployee()).isNull();
    }

    @Test
    @DisplayName("阳性对照: 有打卡记录时照常算 —— ⛔ 闸不许宽到把正常情况也变成 null")
    void withClockRecordsTheNumbersAreComputed() {
        givenUsers(List.of(user(1, "生产部"), user(2, "生产部"), user(3, "品控部"), user(4, "品控部")));
        // 4 个人里 2 个打了卡
        givenClockRecords(List.of(clock(1, 480), clock(1, 60), clock(2, 480)));

        MobileDTO.PersonnelStatistics stats =
                service.getPersonnelStatistics("F006", "2026-08-01", "2026-08-29");

        assertThat(stats.getTotalEmployees()).isEqualTo(4);
        assertThat(stats.getTotalPresent()).as("去重后是 2 个人, ⛔ 不是 3 条记录").isEqualTo(2);
        assertThat(stats.getTotalAbsent()).as("有人打卡而这两位没打 —— 这才是真的缺勤").isEqualTo(2);
        assertThat(stats.getAvgAttendanceRate()).isEqualTo(50.0);
        assertThat(stats.getTotalWorkHours()).isEqualTo((480 + 60 + 480) / 60.0);
        assertThat(stats.getAvgWorkHoursPerEmployee()).isEqualTo((480 + 60 + 480) / 60.0 / 4);
    }

    @Test
    @DisplayName("边界: 全员都打了卡 —— 缺勤 0 是真的 0, 必须给出来")
    void everyoneClockedInMeansZeroAbsentNotNull() {
        givenUsers(List.of(user(1, "生产部"), user(2, "生产部")));
        givenClockRecords(List.of(clock(1, 480), clock(2, 480)));

        MobileDTO.PersonnelStatistics stats =
                service.getPersonnelStatistics("F006", "2026-08-01", "2026-08-29");

        assertThat(stats.getTotalAbsent())
                .as("这里的 0 是「确实没人缺勤」, 与「不知道」是两回事 —— 必须区分得开")
                .isEqualTo(0);
        assertThat(stats.getAvgAttendanceRate()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("边界: 工厂一个人都没有 —— 人数 0, 出勤仍是「算不出」")
    void emptyFactory() {
        givenUsers(new ArrayList<>());
        givenClockRecords(new ArrayList<>());

        MobileDTO.PersonnelStatistics stats =
                service.getPersonnelStatistics("F006", "2026-08-01", "2026-08-29");

        assertThat(stats.getTotalEmployees()).isEqualTo(0);
        assertThat(stats.getAvgAttendanceRate()).isNull();
        assertThat(stats.getTotalAbsent()).isNull();
    }
}
