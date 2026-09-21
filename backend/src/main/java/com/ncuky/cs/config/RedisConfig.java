package com.ncuky.cs.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Lua 脚本注册。
 * <p>
 * 三个脚本都用 DefaultRedisScript 装载，Spring Data Redis 会先尝试 EVALSHA，
 * 脚本未缓存时自动回退到 EVAL —— 省掉每次请求传输整段脚本的开销。
 */
@Configuration
public class RedisConfig {

    private static DefaultRedisScript<Long> load(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(path)));
        script.setResultType(Long.class);
        return script;
    }

    /** 名额原子预扣：1 成功 / 0 已满 / -1 已选过 / -2 未预热 */
    @Bean
    public DefaultRedisScript<Long> deductScript() {
        return load("lua/deduct.lua");
    }

    /** 退选回补：1 成功 / 0 本就不在集合中 / -2 未预热 */
    @Bean
    public DefaultRedisScript<Long> refundScript() {
        return load("lua/refund.lua");
    }

    /** 令牌桶限流：1 放行 / 0 限流 */
    @Bean
    public DefaultRedisScript<Long> tokenBucketScript() {
        return load("lua/token_bucket.lua");
    }

    /**
     * 口令编码器。
     * BCrypt 自带盐，同一口令每次编码结果不同，且比对时从哈希里取盐——
     * 这就是答辩「你的密码怎么存的」的标准答案。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
