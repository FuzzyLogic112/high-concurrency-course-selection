package com.ncuky.cs.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ncuky.cs.entity.WaitingQueue;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface WaitingQueueMapper extends BaseMapper<WaitingQueue> {

    /** 取队首：退选释放名额后按排队序号补位 */
    @Select("SELECT * FROM waiting_queue WHERE teaching_class_id = #{classId} "
            + "AND status = 0 ORDER BY seq ASC LIMIT 1")
    WaitingQueue peekFirst(Long classId);

    @Select("SELECT COALESCE(MAX(seq), 0) FROM waiting_queue WHERE teaching_class_id = #{classId}")
    int maxSeq(Long classId);

    /**
     * 让一条已出队的排队记录重新入队（退选后再次候补）。
     * <p>
     * uk_wq_class_stu 决定了同一对「教学班-学生」只有一行，直接 insert 必然冲突。
     * 条件里带 status <> 0 只复用已经不在排队中的那行：受影响行数为 1 说明重新排上了，
     * 为 0 说明本来就在队里，属于重复请求。
     */
    @Update("UPDATE waiting_queue SET status = 0, seq = #{seq}, created_at = #{now} "
            + "WHERE teaching_class_id = #{classId} AND student_id = #{studentId} AND status <> 0")
    int requeue(@Param("classId") Long classId, @Param("studentId") Long studentId,
                @Param("seq") Integer seq, @Param("now") LocalDateTime now);
}
