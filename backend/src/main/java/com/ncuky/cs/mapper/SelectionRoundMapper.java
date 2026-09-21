package com.ncuky.cs.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ncuky.cs.entity.SelectionRound;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SelectionRoundMapper extends BaseMapper<SelectionRound> {

    @Select("SELECT * FROM selection_round WHERE status = 1 ORDER BY start_time DESC LIMIT 1")
    SelectionRound findCurrent();

    @Update("UPDATE selection_round SET warmed_up = #{warmed} WHERE id = #{id}")
    int markWarmed(Long id, Integer warmed);
}
