package com.ncuky.cs.security;

import com.ncuky.cs.common.BizException;
import com.ncuky.cs.common.ResultCode;
import com.ncuky.cs.entity.SysUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;

    public AuthInterceptor(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        String auth = req.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        UserContext.Principal p = jwtUtil.parse(auth.substring(7));
        if (p == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        // 按 URI 前缀做角色校验。这里只拦「角色对不对」，
        // 「这条数据是不是你的」由各 Controller 自己判——前者是垂直越权，后者是水平越权，
        // 两种都要防，但不能混在一起做。
        String uri = req.getRequestURI();
        if (uri.startsWith("/api/admin")
                && !Integer.valueOf(SysUser.ROLE_ADMIN).equals(p.role())) {
            throw new BizException(ResultCode.FORBIDDEN);
        }
        if (uri.startsWith("/api/teacher")
                && !Integer.valueOf(SysUser.ROLE_TEACHER).equals(p.role())) {
            throw new BizException(ResultCode.FORBIDDEN);
        }
        UserContext.set(p);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse resp,
                                Object handler, Exception ex) {
        // 容器线程会被复用，不清理会把上一个请求的身份泄漏给下一个请求
        UserContext.clear();
    }
}
