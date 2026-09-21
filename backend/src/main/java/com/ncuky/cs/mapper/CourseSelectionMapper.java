package com.ncuky.cs.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ncuky.cs.entity.CourseSelection;
import com.ncuky.cs.dto.Dtos;
import com.ncuky.cs.entity.TeachingClass;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CourseSelectionMapper extends BaseMapper<CourseSelection> {

    /** 对账用：数据库里这个教学班到底有多少条有效选课 */
    @Select("SELECT COUNT(*) FROM course_selection WHERE teaching_class_id = #{classId} AND status = 1")
    int countSelected(Long classId);

    @Select("SELECT * FROM course_selection WHERE student_id = #{studentId} "
            + "AND teaching_class_id = #{classId}")
    CourseSelection findOne(@Param("studentId") Long studentId, @Param("classId") Long classId);

    /** 学生在本学期已选的教学班，用于拼课表位图与算学分 */
    @Select("SELECT tc.* FROM course_selection cs "
            + "JOIN teaching_class tc ON cs.teaching_class_id = tc.id "
            + "WHERE cs.student_id = #{studentId} AND cs.status = 1 AND tc.term = #{term}")
    List<TeachingClass> listSelectedClasses(@Param("studentId") Long studentId,
                                            @Param("term") String term);

    /** 本学期已选学分合计 */
    @Select("SELECT COALESCE(SUM(c.credit), 0) FROM course_selection cs "
            + "JOIN teaching_class tc ON cs.teaching_class_id = tc.id "
            + "JOIN course c ON tc.course_id = c.id "
            + "WHERE cs.student_id = #{studentId} AND cs.status = 1 AND tc.term = #{term}")
    BigDecimal sumSelectedCredit(@Param("studentId") Long studentId, @Param("term") String term);

    /**
     * 已修完的课程 id。
     * <p>
     * 这里以「往期学期选过且有效」近似表示已修完。真实教务系统应查成绩表，
     * 本系统未纳入成绩管理，论文第 7.2 节的不足里要如实写明这一简化。
     */
    @Select("SELECT DISTINCT tc.course_id FROM course_selection cs "
            + "JOIN teaching_class tc ON cs.teaching_class_id = tc.id "
            + "WHERE cs.student_id = #{studentId} AND cs.status = 1 AND tc.term < #{term}")
    List<Long> listCompletedCourseIds(@Param("studentId") Long studentId,
                                      @Param("term") String term);

    /**
     * 只查「当前有效」的选课记录。
     * 退选是软删除（status 置 0，行还在），所以重复选课校验必须带 status 过滤，
     * 否则学生退选之后会被永久判定为「已经选过」，再也选不回来。
     */
    @Select("SELECT * FROM course_selection WHERE student_id = #{studentId} "
            + "AND teaching_class_id = #{classId} AND status = 1")
    CourseSelection findActive(@Param("studentId") Long studentId, @Param("classId") Long classId);

    /**
     * 把一条已退选的记录复活为已选，用于「退选后重新选课」。
     * <p>
     * 条件里带 status = 2 是关键：这一条 UPDATE 同时完成了判断与写入，
     * 受影响行数为 1 说明确实是重选，为 0 说明那行本来就是已选（即重复消息）。
     * 若拆成「先查状态、再更新」，两步之间同样有并发窗口——
     * 这与名额扣减面临的是同一类问题。
     * <p>
     * 字面量 1 与 2 分别对应 {@link com.ncuky.cs.entity.CourseSelection#STATUS_SELECTED}
     * 与 {@code STATUS_DROPPED}。注解里的 SQL 引用不到 Java 常量，改常量时这里要跟着改。
     */
    @Update("UPDATE course_selection SET status = 1, round_id = #{roundId}, updated_at = #{now} "
            + "WHERE student_id = #{studentId} AND teaching_class_id = #{classId} AND status = 2")
    int reactivate(@Param("studentId") Long studentId, @Param("classId") Long classId,
                   @Param("roundId") Long roundId, @Param("now") LocalDateTime now);

    /** 已选课程的教学班 id 集合，前端标「已选」用 */
    @Select("SELECT teaching_class_id FROM course_selection "
            + "WHERE student_id = #{studentId} AND status = 1")
    List<Long> listClassIds(Long studentId);

    /** 某教学班当前所有已选学生，名额预热时用来重建 Redis 里的判重集合 */
    @Select("SELECT student_id FROM course_selection "
            + "WHERE teaching_class_id = #{classId} AND status = 1")
    List<Long> listStudentIds(Long classId);

    /**
     * 教师端：某教学班的选课名单。
     * 学号与姓名分别在 student 与 sys_user 表里，所以要两次 join。
     * 按选课时间排序，先到先得的顺序对教师有意义。
     */
    @Select("SELECT s.id AS studentId, s.student_no AS studentNo, u.real_name AS realName, "
            + "s.class_name AS className, s.grade AS grade, "
            + "CAST(cs.created_at AS VARCHAR) AS selectedAt "
            + "FROM course_selection cs "
            + "JOIN student s ON s.id = cs.student_id "
            + "JOIN sys_user u ON u.id = s.user_id "
            + "WHERE cs.teaching_class_id = #{classId} AND cs.status = 1 "
            + "ORDER BY cs.created_at, s.student_no")
    List<Dtos.RosterItem> listRoster(Long classId);

    /** 实验用：清空某教学班的全部选课记录，让每轮压测从同一起点开始 */
    @Delete("DELETE FROM course_selection WHERE teaching_class_id = #{classId}")
    int deleteByClass(Long classId);
}
