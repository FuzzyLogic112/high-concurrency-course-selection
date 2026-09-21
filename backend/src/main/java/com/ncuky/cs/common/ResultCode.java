package com.ncuky.cs.common;

/**
 * 业务返回码。
 * <p>
 * 选课失败的原因必须能回传给前端并显示成人话，
 * 「提交失败」这种提示在选课这种抢时间的场景里等于没说。
 */
public enum ResultCode {

    OK(0, "成功"),

    // ---- 通用 ----
    PARAM_INVALID(1001, "参数不合法"),
    UNAUTHORIZED(1002, "未登录或登录已过期"),
    FORBIDDEN(1003, "无权访问"),
    RATE_LIMITED(1004, "当前选课人数过多，请稍后重试"),
    SERVER_ERROR(1005, "服务器开小差了"),

    // ---- 选课前置校验 ----
    ROUND_NOT_OPEN(2001, "当前不在选课时间内"),
    ROUND_NOT_MATCH(2002, "本轮次不面向你所在的年级或专业"),
    CLASS_NOT_FOUND(2003, "教学班不存在或已停开"),
    ALREADY_SELECTED(2004, "你已经选过这门课了"),
    TIME_CONFLICT(2005, "上课时间与已选课程冲突"),
    CREDIT_EXCEEDED(2006, "超出本学期可选学分上限"),
    PREREQUISITE_MISSING(2007, "未修完先修课程"),
    MAJOR_GRADE_LIMITED(2008, "本教学班不面向你所在的年级或专业"),

    // ---- 名额扣减 ----
    CLASS_FULL(3001, "名额已满"),
    NOT_WARMED_UP(3002, "名额尚未就绪，请稍后重试"),
    ACCEPTED(3003, "选课请求已受理，正在处理"),

    // ---- 退选 ----
    NOT_SELECTED(4001, "你没有选过这门课"),
    ;

    private final int code;
    private final String msg;

    ResultCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public int code() {
        return code;
    }

    public String msg() {
        return msg;
    }
}
