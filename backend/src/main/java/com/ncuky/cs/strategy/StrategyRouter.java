package com.ncuky.cs.strategy;

import com.ncuky.cs.config.AppProps;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 按配置选策略。
 * <p>
 * app.deduct-strategy 改一下重启即可切换，业务代码一行不动——
 * 这是第 6.3 节四方案对比实验能做到公平的前提。
 */
@Component
public class StrategyRouter {

    private static final Logger log = LoggerFactory.getLogger(StrategyRouter.class);

    private final Map<String, DeductStrategy> strategies;
    private final AppProps props;

    public StrategyRouter(List<DeductStrategy> list, AppProps props) {
        this.strategies = list.stream()
                .collect(Collectors.toMap(DeductStrategy::name, Function.identity()));
        this.props = props;
    }

    @PostConstruct
    public void report() {
        log.info("已注册扣减策略 {}，当前生效：{}", strategies.keySet(), current().name());
    }

    public DeductStrategy current() {
        String key = props.getDeductStrategy();
        DeductStrategy s = strategies.get(key);
        if (s == null) {
            throw new IllegalStateException(
                    "未知的扣减策略 '" + key + "'，可选：" + strategies.keySet());
        }
        return s;
    }
}
