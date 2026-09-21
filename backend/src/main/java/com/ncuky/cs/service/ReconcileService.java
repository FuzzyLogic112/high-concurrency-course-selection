package com.ncuky.cs.service;

import com.ncuky.cs.dto.Dtos;
import com.ncuky.cs.entity.SelectionRound;
import com.ncuky.cs.entity.TeachingClass;
import com.ncuky.cs.mapper.CourseSelectionMapper;
import com.ncuky.cs.mapper.SelectionRoundMapper;
import com.ncuky.cs.mapper.TeachingClassMapper;
import com.ncuky.cs.util.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 缓存与数据库对账。
 * <p>
 * 预扣在 Redis、落库在 MySQL，两者之间存在消息丢失、消费失败、服务宕机等风险。
 * 本地消息表负责「尽量不丢」，这个对账任务负责「丢了能发现」。
 * <p>
 * 论文第 6.5 节的一致性验证，数据就从这里取：
 * 压测跑完调一次 /api/admin/reconcile，偏差必须是 0。
 */
@Service
public class ReconcileService {

    private static final Logger log = LoggerFactory.getLogger(ReconcileService.class);

    private final SelectionRoundMapper roundMapper;
    private final TeachingClassMapper classMapper;
    private final CourseSelectionMapper selectionMapper;
    private final StringRedisTemplate redis;

    public ReconcileService(SelectionRoundMapper roundMapper, TeachingClassMapper classMapper,
                            CourseSelectionMapper selectionMapper, StringRedisTemplate redis) {
        this.roundMapper = roundMapper;
        this.classMapper = classMapper;
        this.selectionMapper = selectionMapper;
        this.redis = redis;
    }

    /**
     * 逐个教学班比对：Redis 里的剩余名额，是否等于 容量 − 数据库实际选课数。
     *
     * @param autoFix true 则把偏差的教学班按数据库口径修正回去
     */
    public Dtos.ReconcileResp reconcile(boolean autoFix) {
        SelectionRound round = roundMapper.findCurrent();
        if (round == null) {
            return new Dtos.ReconcileResp(0, 0, List.of());
        }

        List<TeachingClass> classes = classMapper.listByTerm(round.getTerm());
        List<Dtos.ReconcileItem> items = new ArrayList<>();
        int mismatched = 0;

        for (TeachingClass tc : classes) {
            String v = redis.opsForValue().get(RedisKeys.remain(tc.getId()));
            if (v == null) {
                // 未预热不算偏差，只是还没加载
                continue;
            }
            long redisRemain = Long.parseLong(v);
            int dbSelected = selectionMapper.countSelected(tc.getId());
            long expected = tc.getCapacity() - dbSelected;
            long diff = redisRemain - expected;

            items.add(new Dtos.ReconcileItem(tc.getId(), tc.getClassNo(), tc.getCapacity(),
                    redisRemain, dbSelected, diff));

            if (diff != 0) {
                mismatched++;
                log.warn("对账发现偏差 class={} redis余量={} 期望={} 差值={}",
                        tc.getClassNo(), redisRemain, expected, diff);
                if (autoFix) {
                    redis.opsForValue().set(RedisKeys.remain(tc.getId()),
                            String.valueOf(Math.max(0, expected)));
                    log.info("已按数据库口径修正 class={} -> {}", tc.getClassNo(), expected);
                }
            }
        }
        return new Dtos.ReconcileResp(items.size(), mismatched, items);
    }

    /** 定时对账，只告警不自动改，避免掩盖真实问题 */
    @Scheduled(fixedDelayString = "${app.reconcile.check-interval-ms:30000}")
    public void scheduledCheck() {
        try {
            Dtos.ReconcileResp r = reconcile(false);
            if (r.mismatched() > 0) {
                log.warn("定时对账：{} 个教学班存在偏差，共检查 {} 个", r.mismatched(), r.checked());
            }
        } catch (Exception e) {
            log.error("定时对账执行失败", e);
        }
    }
}
