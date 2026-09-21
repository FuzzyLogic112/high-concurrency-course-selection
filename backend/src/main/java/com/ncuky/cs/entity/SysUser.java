package com.ncuky.cs.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("sys_user")
public class SysUser {

    /** 角色：1 学生 */
    public static final int ROLE_STUDENT = 1;
    /** 角色：2 教师 */
    public static final int ROLE_TEACHER = 2;
    /** 角色：3 教务 */
    public static final int ROLE_ADMIN = 3;

    private Long id;
    private String username;
    /** BCrypt 加盐哈希。禁止明文，禁止不加盐的 MD5/SHA1 */
    private String passwordHash;
    private String realName;
    private Integer role;
    private Integer status;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getRealName() { return realName; }
    public void setRealName(String realName) { this.realName = realName; }
    public Integer getRole() { return role; }
    public void setRole(Integer role) { this.role = role; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
