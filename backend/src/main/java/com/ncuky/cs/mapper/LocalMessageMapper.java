package com.ncuky.cs.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ncuky.cs.entity.LocalMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface LocalMessageMapper extends BaseMapper<LocalMessage> {

    /** 扫描待重投的消息：状态为待投递且到了重试时间 */
    @Select("SELECT * FROM local_message WHERE status = 0 "
            + "AND (next_retry_at IS NULL OR next_retry_at <= #{now}) "
            + "ORDER BY id ASC LIMIT #{limit}")
    List<LocalMessage> listPending(LocalDateTime now, int limit);

    @Update("UPDATE local_message SET status = 1 WHERE biz_key = #{bizKey}")
    int confirm(String bizKey);

    /**
     * 把一条「已确认」的消息重新置为待投递，用于退选之后重新选课。
     * <p>
     * biz_key 是 学生号:教学班号，同一对组合一辈子只有一行。条件里带 status = 1
     * 是为了只复用已经投递完成的那条：若它还在待投递状态，说明是同一次选课的重复请求，
     * 此时不该覆盖，交给原有的幂等逻辑跳过即可。
     */
    @Update("UPDATE local_message SET status = 0, retry_count = 0, next_retry_at = NULL, "
            + "payload = #{payload}, created_at = #{now} "
            + "WHERE biz_key = #{bizKey} AND status = 1")
    int reopen(String bizKey, String payload, LocalDateTime now);

    @Update("UPDATE local_message SET retry_count = retry_count + 1, "
            + "next_retry_at = #{next}, status = #{status} WHERE id = #{id}")
    int markRetry(Long id, LocalDateTime next, Integer status);
}
