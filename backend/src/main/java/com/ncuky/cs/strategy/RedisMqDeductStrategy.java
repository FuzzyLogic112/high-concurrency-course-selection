package com.ncuky.cs.strategy;

import com.ncuky.cs.mq.SelectionMessage;
import com.ncuky.cs.mq.SelectionMessageService;
import com.ncuky.cs.util.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 方案 D：本文方案。Redis + Lua 原子预扣 → 消息队列异步落库。
 * <p>
 * 扣减完全脱离数据库，既不超选（Lua 原子性保证）又没有锁竞争。
 * 预扣成功后只投一条消息就返回受理，真正的写库由消费者按数据库能承受的速率做。
 * <p>
 * 未预热时返回 NOT_WARMED，由上层决定是降级走悲观锁还是直接报错——
 * 这是「缓存击穿」的兜底。
 */
@Component
public class RedisMqDeductStrategy implements DeductStrategy {

    private static final Logger log = LoggerFactory.getLogger(RedisMqDeductStrategy.class);

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> deductScript;
    private final SelectionMessageService messageService;

    public RedisMqDeductStrategy(StringRedisTemplate redis,
                                 DefaultRedisScript<Long> deductScript,
                                 SelectionMessageService messageService) {
        this.redis = redis;
        this.deductScript = deductScript;
        this.messageService = messageService;
    }

    @Override
    public String name() {
        return "redis_mq";
    }

    @Override
    public DeductOutcome deduct(Long classId, Long studentId, Long roundId) {
        Long code = redis.execute(
                deductScript,
                List.of(RedisKeys.remain(classId), RedisKeys.selected(classId)),
                String.valueOf(studentId));

        if (code == null) {
            log.warn("Lua 预扣返回 null，class={} student={}", classId, studentId);
            return DeductOutcome.NOT_WARMED;
        }

        int c = code.intValue();
        switch (c) {
            case 1 -> {
                // 预扣成功。先写本地消息表再投 MQ，保证消息不会因为宕机而丢
                messageService.enqueue(new SelectionMessage(studentId, classId, roundId));
                return DeductOutcome.ACCEPTED;
            }
            case 0 -> {
                return DeductOutcome.FULL;
            }
            case -1 -> {
                return DeductOutcome.DUPLICATE;
            }
            default -> {
                return DeductOutcome.NOT_WARMED;
            }
        }
    }

}
