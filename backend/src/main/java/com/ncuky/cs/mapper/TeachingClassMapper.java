package com.ncuky.cs.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ncuky.cs.entity.TeachingClass;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 教学班 Mapper。
 * <p>
 * 下面四个方法分别对应论文第 6.3 节四方案对比实验里的三种数据库侧扣减做法，
 * 刻意写成裸 SQL 而不是 MyBatis-Plus 的通用方法，
 * 是为了让「每种方案到底发了什么 SQL」在代码里一目了然——答辩要讲的就是这个。
 */
@Mapper
public interface TeachingClassMapper extends BaseMapper<TeachingClass> {

    @Select("SELECT * FROM teaching_class WHERE term = #{term} AND status = 1")
    List<TeachingClass> listByTerm(String term);

    /**
     * 方案 A 直接扣减：读与写分离，中间没有任何保护。
     * 调用方先 selectById 判断 selected_count &lt; capacity，再调本方法，
     * 这两步之间的窗口就是超选的来源。
     */
    @Update("UPDATE teaching_class SET selected_count = selected_count + 1 WHERE id = #{id}")
    int incrementDirect(Long id);

    /**
     * 方案 B 悲观锁：SELECT ... FOR UPDATE 拿行锁。
     * 正确，但把并发请求串行化了，吞吐随并发上升而劣化。
     */
    @Select("SELECT * FROM teaching_class WHERE id = #{id} FOR UPDATE")
    TeachingClass selectForUpdate(Long id);

    /**
     * 方案 C 乐观锁：带版本号的 CAS。
     * 不阻塞，但高并发下大量请求 CAS 失败后重试，重试率随并发飙升。
     * 条件里额外带 selected_count &lt; capacity，保证即使 ABA 也不会超选。
     */
    @Update("UPDATE teaching_class SET selected_count = selected_count + 1, version = version + 1 "
            + "WHERE id = #{id} AND version = #{version} AND selected_count < capacity")
    int incrementCas(@Param("id") Long id, @Param("version") Integer version);

    /** 落库时加计数，redis_mq 策略的消费者用 */
    @Update("UPDATE teaching_class SET selected_count = selected_count + 1 "
            + "WHERE id = #{id} AND selected_count < capacity")
    int incrementGuarded(Long id);

    /** 教师端：某教师本学期的任课教学班。teacher_id 存的是 sys_user.id */
    @Select("SELECT * FROM teaching_class WHERE teacher_id = #{teacherId} "
            + "AND term = #{term} AND status = 1 ORDER BY id")
    List<TeachingClass> listByTeacher(@Param("teacherId") Long teacherId,
                                      @Param("term") String term);

    /** 退选时减计数 */
    @Update("UPDATE teaching_class SET selected_count = selected_count - 1 "
            + "WHERE id = #{id} AND selected_count > 0")
    int decrement(Long id);

    /** 实验用：把教学班计数归零，让每轮压测从同一起点开始 */
    @Update("UPDATE teaching_class SET selected_count = 0, version = 0 WHERE id = #{id}")
    int resetCount(Long id);
}
