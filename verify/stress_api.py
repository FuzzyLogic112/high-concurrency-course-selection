# -*- coding: utf-8 -*-
"""
四方案对比压测 —— 打真实 HTTP 接口

毕业设计《基于缓存与消息队列的高校选课系统的设计与实现》第 6.3 节。

与 verify_deduct.py 的区别：
  verify_deduct.py  只压 Redis，验证 Lua 脚本本身的原子性
  stress_api.py     压完整的业务链路（鉴权 → 限流 → 规则校验 → 扣减 → 落库），
                    这才是论文里真正要写进去的那组数据

每轮都会把教学班彻底复位，四种方案起点完全一致。
运行时切策略，不重启服务，JVM 预热状态与连接池保持一致。

用法：
  python verify/stress_api.py
  python verify/stress_api.py --concurrency 1000 --class-id 1
  python verify/stress_api.py --strategies lua 只跑一种（名字用 redis_mq）

前置：后端已启动（mvn spring-boot:run），Redis 已启动
"""
import argparse
import csv
import json
import os
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor

try:
    import httpx
except ImportError:
    sys.exit("缺少依赖，请执行：pip install httpx")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_BASE = "http://localhost:8080/api"


def login(client, base, username, password="123456"):
    r = client.post(f"{base}/auth/login",
                    json={"username": username, "password": password}, timeout=10)
    r.raise_for_status()
    body = r.json()
    if body.get("code") != 0:
        raise RuntimeError(f"登录失败 {username}: {body}")
    return body["data"]["token"]


def admin_post(client, base, token, path, **kw):
    r = client.post(f"{base}{path}", headers={"Authorization": f"Bearer {token}"},
                    timeout=60, **kw)
    r.raise_for_status()
    return r.json()


def admin_get(client, base, token, path, **kw):
    r = client.get(f"{base}{path}", headers={"Authorization": f"Bearer {token}"},
                   timeout=60, **kw)
    r.raise_for_status()
    return r.json()


def run_round(base, admin_token, tokens, strategy, class_id, capacity, wait_mq):
    """跑一轮：复位 → 切策略 → 并发选课 → 等异步落库 → 取结果"""
    with httpx.Client() as c:
        admin_post(c, base, admin_token, f"/admin/reset-experiment/{class_id}")
        admin_post(c, base, admin_token, "/admin/stats/reset")
        admin_post(c, base, admin_token, f"/admin/strategy?name={strategy}")

    n = len(tokens)
    codes = [None] * n
    barrier = threading.Barrier(n + 1)

    limits = httpx.Limits(max_connections=n + 32, max_keepalive_connections=n + 32)
    client = httpx.Client(limits=limits, timeout=30)

    def worker(i):
        barrier.wait()
        try:
            r = client.post(f"{base}/selection/select",
                            headers={"Authorization": f"Bearer {tokens[i]}"},
                            json={"classId": class_id})
            codes[i] = r.json().get("code")
        except Exception as e:
            codes[i] = f"ERR:{type(e).__name__}"

    with ThreadPoolExecutor(max_workers=n) as ex:
        for i in range(n):
            ex.submit(worker, i)
        barrier.wait()
        t0 = time.perf_counter()
        ex.shutdown(wait=True)
        elapsed = time.perf_counter() - t0

    # redis_mq 是异步落库，要等消费者把队列吃完才能统计最终结果
    if strategy == "redis_mq" and wait_mq:
        deadline = time.time() + 30
        with httpx.Client() as c:
            while time.time() < deadline:
                st = admin_get(c, base, admin_token, "/admin/stats")["data"]
                if st.get("mqQueueDepth", 0) in (0, -1):
                    time.sleep(0.5)
                    break
                time.sleep(0.3)

    with httpx.Client() as c:
        rec = admin_get(c, base, admin_token, "/admin/reconcile")["data"]
        st = admin_get(c, base, admin_token, "/admin/stats")["data"]
    item = next((x for x in rec["items"] if x["classId"] == class_id), None)

    accepted = sum(1 for x in codes if x == 0)
    db_selected = item["dbSelected"] if item else -1
    redis_remain = item["redisRemain"] if item else None
    client.close()

    return {
        "strategy": strategy,
        "concurrency": n,
        "accepted": accepted,
        "db_selected": db_selected,
        "redis_remain": redis_remain,
        "oversell": max(0, db_selected - capacity),
        "diff": item["diff"] if item else None,
        "retries": st.get("optimisticRetries", 0),
        "elapsed_s": round(elapsed, 3),
        "qps": round(n / elapsed) if elapsed > 0 else 0,
    }


def main():
    ap = argparse.ArgumentParser(description="四方案对比压测（真实 HTTP 链路）")
    ap.add_argument("--base", default=DEFAULT_BASE)
    ap.add_argument("--concurrency", type=int, default=500)
    ap.add_argument("--class-id", type=int, default=1)
    ap.add_argument("--capacity", type=int, default=50)
    ap.add_argument("--strategies", default="direct,pessimistic,optimistic,redis_mq")
    ap.add_argument("--no-wait-mq", action="store_true", help="不等异步落库完成（不建议）")
    ap.add_argument("--out", default=os.path.join(ROOT, "verify", "results", "api-stress.csv"))
    args = ap.parse_args()

    with httpx.Client() as c:
        try:
            c.get(f"{args.base}/health", timeout=5).raise_for_status()
        except Exception as e:
            sys.exit(f"后端没起来（{args.base}）：{e}\n"
                     f"先执行：mvn -s tools/settings.xml -f backend/pom.xml spring-boot:run")
        admin_token = login(c, args.base, "admin")
        print(f"登录 {args.concurrency} 个学生账号…", flush=True)
        tokens = []
        for i in range(1, args.concurrency + 1):
            tokens.append(login(c, args.base, f"s{i:04d}"))
            if i % 200 == 0:
                print(f"  {i}/{args.concurrency}", flush=True)

    rows = []
    for s in args.strategies.split(","):
        s = s.strip()
        print(f"\n=== 方案 {s} ===", flush=True)
        r = run_round(args.base, admin_token, tokens, s,
                      args.class_id, args.capacity, not args.no_wait_mq)
        rows.append(r)
        print(json.dumps(r, ensure_ascii=False), flush=True)

    print(f"\n容量 {args.capacity}　并发 {args.concurrency}　打真实 HTTP 链路\n")
    hdr = (f"{'方案':<12}{'受理数':>8}{'库中选课':>10}{'超选':>8}"
           f"{'缓存余量':>10}{'CAS重试':>9}{'耗时(s)':>10}{'QPS':>8}")
    print(hdr)
    print("-" * 80)
    for r in rows:
        print(f"{r['strategy']:<12}{r['accepted']:>8}{r['db_selected']:>10}{r['oversell']:>8}"
              f"{str(r['redis_remain']):>10}{r['retries']:>9}"
              f"{r['elapsed_s']:>10.3f}{r['qps']:>8}")

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)
    print(f"\n已写入 {args.out}")

    print("""
━━━━━━━━━ 这组数据怎么用，两条必须写进论文 ━━━━━━━━━

【1】「超选」这一列是本实验唯一的核心结论，可以直接引用。
     direct 超选、其余三种零超选，是并发控制机制决定的定性结果，
     与机器配置、客户端性能都无关。

【2】「QPS」这一列不能用作方案间的性能对比。
     本脚本用 Python 线程发 HTTP，几百个线程时瓶颈在客户端而不是服务端，
     所以四种方案的 QPS 会趋同（看上面的数字，基本都一样）——
     这说明测的是客户端的天花板，不是服务端的能力。
     论文第 6.3 节要比 QPS 和 P99，必须用 JMeter：
       · 线程组设 500/1000/2000，Ramp-up 设 0
       · 用「察看结果树」之外的聚合报告取吞吐量与 99% 百分位
       · 每轮之间调 /api/admin/reset-experiment/{classId} 复位
     Redis 侧的相对性能对比见 verify_deduct.py，那一组没有 HTTP 开销，
     四种做法的差距是真实的。

【3】「缓存余量」只有 redis_mq 一行有意义。
     另外三种方案直接操作数据库、根本不碰 Redis，
     所以它们跑完之后 Redis 余量仍停在预热时的初始值，这不是 bug。
     对账（/api/admin/reconcile）也只对 redis_mq 有意义——
     它才是以 Redis 作为名额真相的那一种。

环境：本机单机，压测端与服务同机部署。""")


if __name__ == "__main__":
    main()
