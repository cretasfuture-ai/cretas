package com.cretas.aims.ai.tool.impl.hr;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.TimeClockRecord;
import com.cretas.aims.service.TimeClockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 今日考勤查询工具
 *
 * 查询当前用户今天的考勤记录，包括签到签退时间、工作时长等。
 * 作为查询类Tool，无必需参数。
 *
 * Intent Code: ATTENDANCE_TODAY
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class AttendanceTodayTool extends AbstractBusinessTool {

    @Autowired
    private TimeClockService timeClockService;

    @Autowired(required = false)
    private com.cretas.aims.ai.tool.ToolRbacGuard rbacGuard;

    @Autowired
    private com.cretas.aims.repository.UserRepository userRepository;

    @Override
    public String getToolName() {
        return "attendance_today";
    }

    @Override
    public String getDescription() {
        return "查询今日考勤记录。获取当前用户今天的签到签退情况、工作时长统计。" +
                "适用场景：查看今天是否已签到、查看今日工作时长。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // userId: 用户ID（可选，默认当前用户）
        Map<String, Object> userId = new HashMap<>();
        userId.put("type", "string");
        userId.put("description", "用户ID，不传则查询当前登录用户");
        properties.put("userId", userId);
        AttendanceSubject.addNameParameter(properties);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        // 口径规则 8: 没指定别人的 userId 而给了姓名 → 同名的都列出来 (权限 + 工厂范围见 AttendanceSubject.runByName)
        String requestedId = getString(params, "userId");
        if (AttendanceSubject.nameMayDecide(requestedId, context)) {
            Map<String, Object> byName = AttendanceSubject.runByName(getString(params, AttendanceSubject.NAME_PARAM),
                    factoryId, context, rbacGuard, userRepository,
                    id -> timeClockService.getTodayRecord(factoryId, id),
                    data -> AttendanceSubject.todaySummary(data));
            if (byName != null) {
                return byName;
            }
        }
        // The App binds the subject to the logged-in user; another employee only for hr readers (AttendanceSubject).
        Long userId = AttendanceSubject.resolve(getString(params, "userId"), context, rbacGuard);

        try {
            TimeClockRecord record = timeClockService.getTodayRecord(factoryId, userId);
            log.info("查询今日考勤记录: factoryId={}, userId={}", factoryId, userId);
            return buildSimpleResult("查询成功", record);
        } catch (Exception e) {
            log.error("查询今日考勤失败: factoryId={}, userId={}", factoryId, userId, e);
            throw e;
        }
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "userId", "请问您要查询哪位员工的考勤？请提供员工ID或姓名。"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "userId", "用户ID"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
