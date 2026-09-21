package com.ncuky.cs.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** application.yml 里 app.* 的配置绑定。 */
@Component
@ConfigurationProperties(prefix = "app")
public class AppProps {

    private Jwt jwt = new Jwt();
    /** direct / pessimistic / optimistic / redis_mq —— 第 6.3 节对比实验的开关 */
    private String deductStrategy = "redis_mq";
    private RateLimit rateLimit = new RateLimit();
    private Reconcile reconcile = new Reconcile();
    private Mq mq = new Mq();

    public static class Jwt {
        private String secret = "please-change-me-to-a-random-string-at-least-32-chars";
        private long expireMinutes = 120;

        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public long getExpireMinutes() { return expireMinutes; }
        public void setExpireMinutes(long expireMinutes) { this.expireMinutes = expireMinutes; }
    }

    public static class RateLimit {
        private boolean enabled = true;
        /** 令牌产生速率，个/秒 */
        private int qps = 2000;
        /** 桶容量，决定能吃下多大的突发 */
        private int burst = 200;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getQps() { return qps; }
        public void setQps(int qps) { this.qps = qps; }
        public int getBurst() { return burst; }
        public void setBurst(int burst) { this.burst = burst; }
    }

    public static class Reconcile {
        private long retryIntervalMs = 5000;
        private long checkIntervalMs = 30000;

        public long getRetryIntervalMs() { return retryIntervalMs; }
        public void setRetryIntervalMs(long v) { this.retryIntervalMs = v; }
        public long getCheckIntervalMs() { return checkIntervalMs; }
        public void setCheckIntervalMs(long v) { this.checkIntervalMs = v; }
    }

    public static class Mq {
        /** inmemory（dev，免装 RabbitMQ）/ rabbit（prod） */
        private String provider = "inmemory";

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
    }

    public Jwt getJwt() { return jwt; }
    public void setJwt(Jwt jwt) { this.jwt = jwt; }
    public String getDeductStrategy() { return deductStrategy; }
    public void setDeductStrategy(String deductStrategy) { this.deductStrategy = deductStrategy; }
    public RateLimit getRateLimit() { return rateLimit; }
    public void setRateLimit(RateLimit rateLimit) { this.rateLimit = rateLimit; }
    public Reconcile getReconcile() { return reconcile; }
    public void setReconcile(Reconcile reconcile) { this.reconcile = reconcile; }
    public Mq getMq() { return mq; }
    public void setMq(Mq mq) { this.mq = mq; }
}
