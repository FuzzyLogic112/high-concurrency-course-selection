package com.ncuky.cs.ratelimit;

import com.ncuky.cs.config.AppProps;
import com.ncuky.cs.util.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 令牌桶限流，按学生维度。
 * <p>
 * 选课开放瞬间是脉冲流量，用令牌桶而不是漏桶：令牌桶允许一定突发，
 * 更贴合「开放那一秒集中涌入」的真实场景。
 * <p>
 * 这也是防脚本刷课的第一道闸门——答辩问「有人写脚本刷课怎么办」，答这个。
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> tokenBucketScript;
    private final AppProps props;

    public RateLimiter(StringRedisTemplate redis,
                       DefaultRedisScript<Long> tokenBucketScript,
                       AppProps props) {
        this.redis = redis;
        this.tokenBucketScript = tokenBucketScript;
        this.props = props;
    }

    /** @return true 放行，false 被限流 */
    public boolean tryAcquire(Long studentId) {
        AppProps.RateLimit cfg = props.getRateLimit();
        if (!cfg.isEnabled()) {
            return true;
        }
        try {
            Long allowed = redis.execute(
                    tokenBucketScript,
                    List.of(RedisKeys.rateLimit(studentId)),
                    String.valueOf(cfg.getQps()),
                    String.valueOf(cfg.getBurst()),
                    String.valueOf(System.currentTimeMillis()),
                    "1");
            return allowed != null && allowed == 1L;
        } catch (Exception e) {
            // Redis 挂了不能把所有人都挡在门外，限流失效总好过服务不可用
            log.warn("限流脚本执行失败，放行 student={}", studentId, e);
            return true;
        }
    }
}
