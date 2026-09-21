package com.ncuky.cs.controller;

import com.ncuky.cs.common.R;
import com.ncuky.cs.dto.Dtos;
import com.ncuky.cs.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/auth/login")
    public R<Dtos.LoginResp> login(@Valid @RequestBody Dtos.LoginReq req) {
        return R.ok(authService.login(req));
    }

    /** 免鉴权的健康检查，压测脚本用它确认服务起来了 */
    @GetMapping("/health")
    public R<Map<String, Object>> health() {
        return R.ok(Map.of("status", "UP", "ts", System.currentTimeMillis()));
    }
}
