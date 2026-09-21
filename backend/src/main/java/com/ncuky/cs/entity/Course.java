package com.ncuky.cs.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;

@TableName("course")
public class Course {

    private Long id;
    private String courseNo;
    private String name;
    private BigDecimal credit;
    private Integer hours;
    /** 1 必修 2 限选 3 任选 */
    private Integer nature;
    /** 先修课程 id 数组的 JSON 文本，如 [12,35]；为空表示无先修要求 */
    private String prerequisiteIds;
    private Long deptId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCourseNo() { return courseNo; }
    public void setCourseNo(String courseNo) { this.courseNo = courseNo; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BigDecimal getCredit() { return credit; }
    public void setCredit(BigDecimal credit) { this.credit = credit; }
    public Integer getHours() { return hours; }
    public void setHours(Integer hours) { this.hours = hours; }
    public Integer getNature() { return nature; }
    public void setNature(Integer nature) { this.nature = nature; }
    public String getPrerequisiteIds() { return prerequisiteIds; }
    public void setPrerequisiteIds(String prerequisiteIds) { this.prerequisiteIds = prerequisiteIds; }
    public Long getDeptId() { return deptId; }
    public void setDeptId(Long deptId) { this.deptId = deptId; }
}
