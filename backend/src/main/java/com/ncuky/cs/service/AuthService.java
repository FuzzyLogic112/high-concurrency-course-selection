package com.ncuky.cs.service;

import com.ncuky.cs.common.BizException;
import com.ncuky.cs.common.ResultCode;
import com.ncuky.cs.dto.Dtos;
import com.ncuky.cs.entity.Student;
import com.ncuky.cs.entity.SysUser;
import com.ncuky.cs.mapper.StudentMapper;
import com.ncuky.cs.mapper.SysUserMapper;
import com.ncuky.cs.security.JwtUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final SysUserMapper userMapper;
    private final StudentMapper studentMapper;
    private final PasswordEncoder encoder;
    private final JwtUtil jwtUtil;

    public AuthService(SysUserMapper userMapper, StudentMapper studentMapper,
                       PasswordEncoder encoder, JwtUtil jwtUtil) {
        this.userMapper = userMapper;
        this.studentMapper = studentMapper;
        this.encoder = encoder;
        this.jwtUtil = jwtUtil;
    }

    public Dtos.LoginResp login(Dtos.LoginReq req) {
        SysUser user = userMapper.findByUsername(req.username());
        // 用户不存在与口令错误返回同样的提示，不给撞库留信息
        if (user == null || !encoder.matches(req.password(), user.getPasswordHash())) {
            throw new BizException(ResultCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BizException(ResultCode.FORBIDDEN, "账号已禁用");
        }

        Student student = studentMapper.findByUserId(user.getId());
        Long sid = student == null ? null : student.getId();
        String sno = student == null ? null : student.getStudentNo();

        String token = jwtUtil.issue(user.getId(), user.getRole(), sid, user.getUsername());
        return new Dtos.LoginResp(token, user.getRole(), user.getRealName(), sid, sno);
    }
}
