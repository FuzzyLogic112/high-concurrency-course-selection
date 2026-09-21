package com.ncuky.cs.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;

@TableName("student")
public class Student {

    private Long id;
    private Long userId;
    private String studentNo;
    private Long majorId;
    private Integer grade;
    private String className;
    /** 已修学分 */
    private BigDecimal creditEarned;
    /** 本学期可选学分上限 */
    private BigDecimal creditLimit;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getStudentNo() { return studentNo; }
    public void setStudentNo(String studentNo) { this.studentNo = studentNo; }
    public Long getMajorId() { return majorId; }
    public void setMajorId(Long majorId) { this.majorId = majorId; }
    public Integer getGrade() { return grade; }
    public void setGrade(Integer grade) { this.grade = grade; }
    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }
    public BigDecimal getCreditEarned() { return creditEarned; }
    public void setCreditEarned(BigDecimal creditEarned) { this.creditEarned = creditEarned; }
    public BigDecimal getCreditLimit() { return creditLimit; }
    public void setCreditLimit(BigDecimal creditLimit) { this.creditLimit = creditLimit; }
}
