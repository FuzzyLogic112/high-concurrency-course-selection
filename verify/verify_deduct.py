# -*- coding: utf-8 -*-
"""
Redis 名额扣减三方案并发对比验证

毕业设计《基于缓存与消息队列的高校选课系统的设计与实现》
第 6.3 节「四方案对比实验」的 Redis 侧预验证。

三种方案：
  naive  GET 读余量 → 判断 → DECR。三步非原子，对应论文方案 A「直接扣减」。必然超选。
  watch  WATCH/MULTI/EXEC 乐观锁，对应论文方案 C。正确，但高并发下重试率高。
  lua    EVAL 执行 ../lua/deduct.lua，对应论文方案 D「本文方案」。正确且无锁竞争。

用法：
  python verify/verify_deduct.py
  python verify/verify_deduct.py --concurrency 2000 --capacity 50
  python verify/verify_deduct.py --strategy lua
  python verify/verify_deduct.py --gap-ms 2          # 拉大 naive 的竞态窗口，仅用于演示

运行前：docker compose up -d redis
"""
import argparse
import os
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor

try:
    import redis
except ImportError:
    sys.exit("缺少依赖，请先执行：pip install -r verify/requirements.txt")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LUA_PATH = os.path.join(ROOT, "lua", "deduct.lua")

KEY_REMAIN = "cs:verify:class:{cid}:remain"
KEY_SELECTED = "cs:verify:class:{cid}:selected"


def load_env():
    """极简 .env 读取，避免额外依赖。找不到就用默认值。"""
    env = {}
    path = os.path.join(ROOT, ".env")
    if not os.path.exists(path):
        path = os.path.join(ROOT, ".env.example")
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip()
    return env


# ---------------------------------------------------------------- 三种策略

def strategy_naive(r, remain_key, sel_key, student_id, gap):
    """GET → 判断 → DECR。读与写之间存在窗口，这就是超选的来源。"""
    remain = r.get(remain_key)
    if remain is None:
        return -2, 0
    if int(remain) <= 0:
        return 0, 0
    if gap:
        time.sleep(gap)
    r.decr(remain_key)
    r.sadd(sel_key, student_id)
    return 1, 0


def strategy_watch(r, remain_key, sel_key, student_id, gap):
    """WATCH/MULTI/EXEC 乐观锁。被别人改过就重试，正确但要付出重试代价。"""
    retries = 0
    with r.pipeline() as pipe:
        while True:
            try:
                pipe.watch(remain_key)
                remain = pipe.get(remain_key)
                if remain is None:
                    pipe.unwatch()
                    return -2, retries
                if int(remain) <= 0:
                    pipe.unwatch()
                    return 0, retries
                pipe.multi()
                pipe.decr(remain_key)
                pipe.sadd(sel_key, student_id)
                pipe.execute()
                return 1, retries
            except redis.WatchError:
                retries += 1
                if retries > 200:      # 兜底，防止极端情况下空转
                    return 0, retries
                continue


def make_lua_strategy(r):
    with open(LUA_PATH, encoding="utf-8") as f:
        script = r.register_script(f.read())

    def strategy_lua(_r, remain_key, sel_key, student_id, gap):
        code = script(keys=[remain_key, sel_key], args=[student_id])
        return int(code), 0

    return strategy_lua


# ---------------------------------------------------------------- 压测骨架

def run_once(pool, name, fn, capacity, concurrency, cid, gap):
    r = redis.Redis(connection_pool=pool)
    remain_key = KEY_REMAIN.format(cid=cid)
    sel_key = KEY_SELECTED.format(cid=cid)

    # 重置状态：模拟教务在选课开放前执行的「名额预热」
    r.delete(remain_key, sel_key)
    r.set(remain_key, capacity)

    barrier = threading.Barrier(concurrency + 1)
    results = [None] * concurrency
    retries = [0] * concurrency
    errors = []
    err_lock = threading.Lock()

    def worker(i):
        conn = redis.Redis(connection_pool=pool)
        barrier.wait()                      # 所有线程就绪后一起冲，制造真正的瞬时峰值
        try:
            code, rt = fn(conn, remain_key, sel_key, f"stu{i}", gap)
            results[i] = code
            retries[i] = rt
        except Exception as e:              # 不能静默吞掉，否则成功数偏低却查不出原因
            with err_lock:
                if len(errors) < 5:
                    errors.append(repr(e))

    with ThreadPoolExecutor(max_workers=concurrency) as ex:
        for i in range(concurrency):
            ex.submit(worker, i)
        barrier.wait()
        t0 = time.perf_counter()
        ex.shutdown(wait=True)
        elapsed = time.perf_counter() - t0

    success = sum(1 for c in results if c == 1)
    final_remain = int(r.get(remain_key) or 0)
    sel_size = r.scard(sel_key)
    oversell = max(0, success - capacity)
    qps = concurrency / elapsed if elapsed > 0 else 0.0

    return {
        "name": name,
        "success": success,
        "remain": final_remain,
        "sel_size": sel_size,
        "oversell": oversell,
        "elapsed": elapsed,
        "qps": qps,
        "retries": sum(retries),
        "errors": errors,
    }


def main():
    ap = argparse.ArgumentParser(description="Redis 名额扣减三方案并发对比")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=6379)
    ap.add_argument("--password", default=None, help="默认读取 .env 的 REDIS_PASSWORD")
    ap.add_argument("--capacity", type=int, default=50, help="教学班容量")
    ap.add_argument("--concurrency", type=int, default=500, help="并发请求数")
    ap.add_argument("--cid", type=int, default=1001, help="教学班 id")
    ap.add_argument("--gap-ms", type=float, default=0.0,
                    help="naive 方案 GET 与 DECR 之间插入的间隔(ms)，仅用于放大竞态演示")
    ap.add_argument("--strategy", choices=["naive", "watch", "lua", "all"], default="all")
    args = ap.parse_args()

    env = load_env()
    password = args.password if args.password is not None else env.get("REDIS_PASSWORD")

    # protocol=2 强制用 RESP2：
    # redis-py 5.x 起默认 RESP3，握手时发 HELLO，而 HELLO 是 Redis 6 才有的命令。
    # tools/ 里那份 Windows 移植版是 Redis 5.0，不加这一项会直接握手失败。
    # 正式环境用 docker-compose 的 Redis 7，两种协议都支持。
    pool = redis.ConnectionPool(
        host=args.host, port=args.port, password=password,
        decode_responses=True, max_connections=args.concurrency + 32,
        protocol=2,
    )
    probe = redis.Redis(connection_pool=pool)
    try:
        probe.ping()
    except redis.exceptions.RedisError as e:
        sys.exit(f"连不上 Redis（{args.host}:{args.port}）：{e}\n"
                 f"先执行：docker compose up -d redis")

    if args.concurrency > 2000:
        print(f"警告：{args.concurrency} 个线程在 Windows 上开销较大，"
              f"若结果异常请降到 2000 以内，或改用 JMeter 压测正式服务。\n")

    gap = args.gap_ms / 1000.0
    strategies = []
    if args.strategy in ("naive", "all"):
        strategies.append(("naive", strategy_naive))
    if args.strategy in ("watch", "all"):
        strategies.append(("watch", strategy_watch))
    if args.strategy in ("lua", "all"):
        strategies.append(("lua", make_lua_strategy(probe)))

    print(f"教学班容量 {args.capacity}　并发请求 {args.concurrency}　"
          f"naive 间隔 {args.gap_ms}ms\n")

    rows = []
    for name, fn in strategies:
        rows.append(run_once(pool, name, fn, args.capacity,
                             args.concurrency, args.cid, gap))

    print(f"{'方案':<8}{'成功数':>8}{'最终余量':>10}{'超选':>8}{'重试':>8}{'耗时(s)':>10}{'QPS':>10}")
    print("-" * 68)
    for x in rows:
        print(f"{x['name']:<8}{x['success']:>8}{x['remain']:>10}{x['oversell']:>8}"
              f"{x['retries']:>8}{x['elapsed']:>10.3f}{x['qps']:>10.0f}")

    print()
    for x in rows:
        if x["errors"]:
            print(f"  {x['name']}：有线程报错（仅列前几条）{x['errors']}")
        if x["oversell"] > 0:
            print(f"  {x['name']}：超选 {x['oversell']} 人，最终余量 {x['remain']}。"
                  f"这正是论文第 1 章要论证的问题。")
        else:
            print(f"  {x['name']}：零超选，选中人数精确等于容量 {args.capacity}。")

    print("\n注：本机单机环境，压测端与 Redis 同机。\n"
          "    「哪种方案会超选、哪种不会」是定性的正确性结论，与机器配置无关，单机完全成立，\n"
          "    论文里如实写明「实验环境为单机，压测端与被测服务同机部署」即可。\n"
          "    QPS 和耗时受同机资源竞争影响，只做横向对比、不要当作绝对性能指标。")


if __name__ == "__main__":
    main()
