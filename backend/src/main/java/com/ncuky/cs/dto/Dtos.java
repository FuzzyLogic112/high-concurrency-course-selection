package com.ncuky.cs.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** 接口出入参。集中放在一个文件里，省得为每个小 record 单开一个类文件。 */
public final class Dtos {

    private Dtos() {
    }

    public record LoginReq(@NotBlank String username, @NotBlank String password) {
    }

    public record LoginResp(String token, Integer role, String realName,
                            Long studentId, String studentNo) {
    }

    public record SelectReq(@NotNull Long classId) {
    }

    /**
     * 选课结果。
     * redis_mq 策略下 accepted=true 表示「已受理」，最终结果要拿 ticket 再查一次；
     * 其余三种策略是同步落库，accepted=true 即已经选上。
     */
    public record SelectResp(boolean accepted, String ticket, String strategy,
                             int code, String msg) {
    }

    /** 选课前置校验结果，用于前端点「选课」之前先给提示 */
    public record PreCheckResp(boolean passed, int code, String msg, List<String> conflicts) {
    }

    /** 教师端：我任教的一个教学班 */
    public record TeacherClassVO(Long id, String classNo, String courseNo, String courseName,
                                 java.math.BigDecimal credit, Integer capacity,
                                 Integer selectedCount, String timeSlots, String location) {
    }

    /** 教师端：选课名单里的一名学生 */
    public record RosterItem(Long studentId, String studentNo, String realName,
                             String className, Integer grade, String selectedAt) {
    }

    public record TeachingClassVO(Long id, String classNo, String courseNo, String courseName,
                                  String teacherName, java.math.BigDecimal credit,
                                  Integer capacity, Integer selectedCount, Long remain,
                                  String timeSlots, String location, boolean selected) {
    }

    public record TimetableItem(Long classId, String courseName, String teacherName,
                                String timeSlots, String location,
                                java.math.BigDecimal credit) {
    }

    /** 对账明细：缓存余量与数据库实际选课数的偏差 */
    public record ReconcileItem(Long classId, String classNo, Integer capacity,
                                Long redisRemain, Integer dbSelected, Long diff) {
    }

    public record ReconcileResp(int checked, int mismatched, List<ReconcileItem> items) {
    }

    public record WarmupResp(Long roundId, int classCount, long costMs) {
    }
}
