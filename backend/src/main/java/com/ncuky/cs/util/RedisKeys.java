package com.ncuky.cs.util;

/**
 * Redis key 规范。
 * <p>
 * 统一在这里定义，避免散落在各处拼字符串——
 * 对账任务和预热任务必须和选课链路用完全相同的 key，否则永远对不平。
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    private static final String PREFIX = "cs:";

    /** 教学班剩余名额（string，整数） */
    public static String remain(Long classId) {
        return PREFIX + "class:" + classId + ":remain";
    }

    /** 教学班已选学生集合（set），用于 Lua 内判重 */
    public static String selected(Long classId) {
        return PREFIX + "class:" + classId + ":selected";
    }

    /** 学生课表位图缓存（hash: lo / hi），命中则免查库 */
    public static String timetable(Long studentId) {
        return PREFIX + "student:" + studentId + ":timetable";
    }

    /** 学生已修+在选学分缓存 */
    public static String credit(Long studentId) {
        return PREFIX + "student:" + studentId + ":credit";
    }

    /** 选课受理凭证 -> 处理结果 */
    public static String ticket(String token) {
        return PREFIX + "ticket:" + token;
    }

    /** 令牌桶限流，按学生维度 */
    public static String rateLimit(Long studentId) {
        return PREFIX + "rl:stu:" + studentId;
    }

    /** 已预热的轮次标记 */
    public static String warmed(Long roundId) {
        return PREFIX + "round:" + roundId + ":warmed";
    }
}
