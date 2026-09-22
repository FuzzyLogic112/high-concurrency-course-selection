import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 选课接口压测端 —— 四方案对比实验的取数工具。
 *
 * 为什么不用 Python：
 *   verify/stress_api.py 用 Python 线程发 HTTP，500 并发时瓶颈在客户端不在服务端，
 *   四种方案 QPS 全部趋同在 90 左右，测到的是客户端天花板而不是服务端能力。
 *   这里改用 JDK 21+ 的虚拟线程，一个虚拟线程只占几百字节栈，
 *   几千并发对客户端几乎没有压力，测出来的 QPS 与 P99 才是服务端的真实表现。
 *
 * 为什么不用 JMeter：
 *   JMeter 当然也行，但它要装 90MB 的包，而且图形界面跑压测本身就吃资源。
 *   本工具只依赖 JDK 自带的 java.net.http，javac 一编译就能跑。
 *   论文里两种都可以写，结论一致。
 *
 * 一次运行做的事：
 *   1. 教务登录 → 复位教学班 → 切换扣减策略
 *   2. 预登录 N 个学生拿 token（必须在计时之外做完：
 *      BCrypt 校验单次约 100ms，混进计时会把测量完全淹没）
 *   3. 所有虚拟线程在栅栏处对齐，同时发起选课请求
 *   4. 等待异步落库排空，再读对账接口拿最终落库数
 *   5. 输出 accepted / 落库数 / 超选 / QPS / P50 / P95 / P99
 *
 * 编译运行：
 *   javac -d verify/loadtest/out verify/loadtest/LoadTest.java
 *   java -cp verify/loadtest/out LoadTest --strategy redis_mq --concurrency 2000
 */
public class LoadTest {

    // ---------------------------------------------------------------- 参数

    static String baseUrl = "http://localhost:8080";
    static String strategy = "redis_mq";
    static int concurrency = 1000;
    static long classId = 1;
    static String adminUser = "admin";
    static String password = "123456";
    static boolean csv = false;

    static HttpClient client;
    static String adminToken;

    public static void main(String[] args) throws Exception {
        parseArgs(args);

        // 虚拟线程执行器：每个请求一个虚拟线程，不需要调线程池大小
        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(exec)
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        if (!csv) {
            System.out.printf("压测端：Java 虚拟线程　目标：%s%n", baseUrl);
            System.out.printf("策略：%s　并发：%d　教学班：%d%n%n", strategy, concurrency, classId);
        }

        adminToken = login(adminUser, password);
        if (adminToken == null) {
            System.err.println("教务登录失败，确认后端已启动且账号为 admin/123456");
            System.exit(1);
        }

        int capacity = resetAndPrepare();
        List<String> tokens = prepareStudentTokens(concurrency);

        // 并发数由「拿到多少个互不相同的学生 token」决定 —— 每个请求必须是不同学生，
        // 否则 Lua 脚本的 SISMEMBER 判重会把第二个起的请求全挡掉，测出来的是判重而不是名额争抢。
        // 种子学生数不足时登录会静默失败，若不在这里叫停，CSV 里会记下一个从未真正跑过的并发数。
        if (tokens.size() < concurrency) {
            System.err.printf("只拿到 %d 个学生 token，达不到要求的 %d 并发。%n", tokens.size(), concurrency);
            System.err.printf("种子学生数需不少于并发数：启动后端时设 app.seed.students=%d，%n", concurrency);
            System.err.println("并删除 verify/loadtest/tokens.cache 让它重新登录。");
            System.exit(1);
        }

        Result r = fire(tokens, capacity);
        report(r, capacity);
        exec.shutdown();
    }

    // ---------------------------------------------------------------- 准备

    /** 复位教学班 + 切策略，保证每轮从完全相同的起点开始 */
    static int resetAndPrepare() throws Exception {
        post("/api/admin/reset-experiment/" + classId, null, adminToken);
        post("/api/admin/strategy?name=" + strategy, null, adminToken);

        String stats = get("/api/admin/stats", adminToken);
        String actual = str(stats, "deductStrategy");
        if (!strategy.equals(actual)) {
            System.err.println("策略切换未生效，服务端仍是 " + actual);
            System.exit(1);
        }
        String rec = get("/api/admin/reconcile?autoFix=false", adminToken);
        int capacity = capacityOf(rec, classId);
        if (!csv) {
            System.out.printf("已复位教学班 %d，容量 %d，策略确认为 %s%n", classId, capacity, actual);
        }
        return capacity;
    }

    /**
     * 预登录拿 token。
     * 登录本身要做 BCrypt，单次约 100ms，绝不能混进计时区间。
     * token 缓存到文件，重复跑不同策略时直接复用，省掉几十秒。
     */
    static List<String> prepareStudentTokens(int n) throws Exception {
        Path cache = Path.of("verify/loadtest/tokens.cache");
        if (Files.exists(cache)) {
            List<String> cached = Files.readAllLines(cache);
            if (cached.size() >= n) {
                if (!csv) System.out.printf("复用缓存的 %d 个 token%n", n);
                return new ArrayList<>(cached.subList(0, n));
            }
        }

        if (!csv) System.out.printf("预登录 %d 个学生（BCrypt 较慢，只做一次）…", n);
        String[] out = new String[n];
        AtomicInteger done = new AtomicInteger();
        // 限制登录并发，避免 BCrypt 把服务端 CPU 打满导致超时
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    out[idx] = login(String.format("s%04d", idx + 1), password);
                } catch (Exception ignored) {
                    // 留空，下面统一过滤
                } finally {
                    done.incrementAndGet();
                    latch.countDown();
                }
            });
        }
        latch.await();
        pool.shutdown();

        List<String> list = new ArrayList<>();
        for (String t : out) {
            if (t != null) list.add(t);
        }
        if (!csv) System.out.printf(" 成功 %d 个%n", list.size());
        Files.write(cache, list);
        return list;
    }

    // ---------------------------------------------------------------- 压测

    record Result(int accepted, int rejected, int failed, long elapsedNanos, long[] latencies) {
    }

    static Result fire(List<String> tokens, int capacity) throws Exception {
        int n = tokens.size();
        String body = "{\"classId\":" + classId + "}";

        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(n);

        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        ConcurrentLinkedQueue<Long> lats = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();

        for (String token : tokens) {
            Thread.ofVirtual().start(() -> {
                ready.countDown();
                try {
                    go.await();                       // 所有线程在这里对齐，制造真正的瞬时峰值
                    long t0 = System.nanoTime();
                    HttpResponse<String> resp = client.send(
                            HttpRequest.newBuilder(URI.create(baseUrl + "/api/selection/select"))
                                    .header("Content-Type", "application/json")
                                    .header("Authorization", "Bearer " + token)
                                    .timeout(Duration.ofSeconds(30))
                                    .POST(HttpRequest.BodyPublishers.ofString(body))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    lats.add(System.nanoTime() - t0);
                    // data.accepted 为 true 表示同步选上或已受理
                    if (resp.statusCode() == 200 && resp.body().contains("\"accepted\":true")) {
                        accepted.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                    }
                } catch (Exception e) {
                    failed.incrementAndGet();
                    // 失败原因必须留证。带着不明失败率出来的 QPS 是不能写进论文的
                    if (errors.size() < 200) {
                        errors.add(e.getClass().getSimpleName()
                                + (e.getMessage() == null ? "" : ": " + e.getMessage()));
                    }
                } finally {
                    finished.countDown();
                }
            });
        }

        ready.await();
        if (!csv) System.out.printf("%d 个虚拟线程已就绪，开始…%n", n);
        long start = System.nanoTime();
        go.countDown();
        finished.await();
        long elapsed = System.nanoTime() - start;

        drainQueue();
        long[] arr = lats.stream().mapToLong(Long::longValue).sorted().toArray();

        if (!errors.isEmpty() && !csv) {
            System.out.println("\n失败原因分布：");
            errors.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            s -> s, java.util.stream.Collectors.counting()))
                    .entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(5)
                    .forEach(e -> System.out.printf("  %4d 次  %s%n", e.getValue(), e.getKey()));
        }
        return new Result(accepted.get(), rejected.get(), failed.get(), elapsed, arr);
    }

    /**
     * 等异步落库排空。
     * redis_mq 是「受理即返回」，请求全部返回时消息可能还堆在队列里，
     * 这时候去数数据库会少算，必须等队列深度归零再读。
     */
    static void drainQueue() throws Exception {
        for (int i = 0; i < 120; i++) {
            String s = get("/api/admin/stats", adminToken);
            if (num(s, "mqQueueDepth") == 0) {
                Thread.sleep(300);                    // 再等一下最后一批落库提交
                String again = get("/api/admin/stats", adminToken);
                if (num(again, "mqQueueDepth") == 0) return;
            }
            Thread.sleep(200);
        }
        System.err.println("警告：队列未在 25 秒内排空，落库数可能偏少");
    }

    // ---------------------------------------------------------------- 输出

    static void report(Result r, int capacity) throws Exception {
        String rec = get("/api/admin/reconcile?autoFix=false", adminToken);
        int dbSelected = dbSelectedOf(rec, classId);
        long redisRemain = redisRemainOf(rec, classId);
        String stats = get("/api/admin/stats", adminToken);
        long retries = num(stats, "optimisticRetries");

        int total = r.accepted() + r.rejected() + r.failed();
        double sec = r.elapsedNanos() / 1e9;
        double qps = sec > 0 ? total / sec : 0;
        int oversell = Math.max(0, dbSelected - capacity);

        if (csv) {
            System.out.printf("%s,%d,%d,%d,%d,%d,%d,%d,%.3f,%.0f,%.1f,%.1f,%.1f,%.1f%n",
                    strategy, concurrency, r.accepted(), dbSelected, redisRemain, oversell,
                    retries, r.failed(), sec, qps,
                    ms(pct(r.latencies(), 50)), ms(pct(r.latencies(), 95)),
                    ms(pct(r.latencies(), 99)), ms(max(r.latencies())));
            return;
        }

        System.out.println();
        System.out.println("────────────────────────────────────────────");
        System.out.printf("  策略             %s%n", strategy);
        System.out.printf("  并发 / 容量      %d / %d%n", total, capacity);
        System.out.printf("  接口返回受理     %d%n", r.accepted());
        System.out.printf("  数据库实际落库   %d%n", dbSelected);
        System.out.printf("  超选             %d %s%n", oversell,
                oversell > 0 ? "  ← 选中人数超过容量" : "  ← 精确等于容量");
        System.out.printf("  Redis 余量       %d%n", redisRemain);
        System.out.printf("  CAS 重试         %d%n", retries);
        System.out.printf("  请求失败         %d%n", r.failed());
        System.out.println("  ──────────────────────────────");
        System.out.printf("  耗时             %.3f s%n", sec);
        System.out.printf("  QPS              %.0f%n", qps);
        System.out.printf("  P50 / P95 / P99  %.1f / %.1f / %.1f ms%n",
                ms(pct(r.latencies(), 50)), ms(pct(r.latencies(), 95)),
                ms(pct(r.latencies(), 99)));
        System.out.printf("  最大延迟         %.1f ms%n", ms(max(r.latencies())));
        System.out.println("────────────────────────────────────────────");
    }

    static double ms(long nanos) {
        return nanos / 1e6;
    }

    static long pct(long[] sorted, int p) {
        if (sorted.length == 0) return 0;
        int idx = (int) Math.ceil(sorted.length * p / 100.0) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }

    static long max(long[] sorted) {
        return sorted.length == 0 ? 0 : sorted[sorted.length - 1];
    }

    // ---------------------------------------------------------------- HTTP

    static String login(String user, String pass) throws Exception {
        String body = "{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}";
        String resp = post("/api/auth/login", body, null);
        return str(resp, "token");
    }

    static String get(String path, String token) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30)).GET();
        if (token != null) b.header("Authorization", "Bearer " + token);
        return client.send(b.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
    }

    static String post(String path, String body, String token)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return client.send(b.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
    }

    // ------------------------------------------------- 极简 JSON 取值
    // 只取几个固定字段，不值得为此引入 Jackson 依赖

    static String str(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    static long num(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }

    /** 从对账结果里取指定教学班那一项 */
    static String itemOf(String json, long id) {
        Matcher m = Pattern.compile("\\{[^{}]*\"classId\"\\s*:\\s*" + id + "[^{}]*\\}")
                .matcher(json);
        return m.find() ? m.group() : "";
    }

    static int capacityOf(String json, long id) {
        return (int) num(itemOf(json, id), "capacity");
    }

    static int dbSelectedOf(String json, long id) {
        return (int) num(itemOf(json, id), "dbSelected");
    }

    static long redisRemainOf(String json, long id) {
        return num(itemOf(json, id), "redisRemain");
    }

    // ---------------------------------------------------------------- 参数解析

    static void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--base-url" -> baseUrl = args[++i];
                case "--strategy" -> strategy = args[++i];
                case "--concurrency" -> concurrency = Integer.parseInt(args[++i]);
                case "--class-id" -> classId = Long.parseLong(args[++i]);
                case "--csv" -> csv = true;
                case "--help" -> {
                    System.out.println("""
                            用法：java -cp out LoadTest [选项]
                              --strategy     direct | pessimistic | optimistic | redis_mq
                              --concurrency  并发请求数，默认 1000
                              --class-id     教学班 id，默认 1
                              --base-url     后端地址，默认 http://localhost:8080
                              --csv          只输出一行 CSV，便于批量汇总
                            """);
                    System.exit(0);
                }
                default -> {
                    System.err.println("未知参数：" + args[i]
                            + "，可用参数见 --help；已忽略 " + Arrays.toString(args));
                }
            }
        }
    }
}
