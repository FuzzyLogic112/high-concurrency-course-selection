package com.ncuky.cs.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ncuky.cs.entity.Student;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface StudentMapper extends BaseMapper<Student> {

    @Select("SELECT * FROM student WHERE user_id = #{userId}")
    Student findByUserId(Long userId);

    @Select("SELECT * FROM student WHERE student_no = #{studentNo}")
    Student findByStudentNo(String studentNo);
}
