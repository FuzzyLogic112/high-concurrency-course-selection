package com.ncuky.cs.security;

/**
 * 当前登录用户。
 * <p>
 * 选课接口的学生身份一律从这里取，绝不接受前端传入的 studentId ——
 * 否则学生改个请求参数就能替别人选课，这是答辩必问的越权点。
 */
public final class UserContext {

    public record Principal(Long userId, Integer role, Long studentId, String username) {
    }

    private static final ThreadLocal<Principal> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(Principal p) {
        HOLDER.set(p);
    }

    public static Principal get() {
        return HOLDER.get();
    }

    public static Long studentId() {
        Principal p = HOLDER.get();
        return p == null ? null : p.studentId();
    }

    public static Long userId() {
        Principal p = HOLDER.get();
        return p == null ? null : p.userId();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
