package com.ncuky.cs.init;

import com.ncuky.cs.entity.SysUser;
import com.ncuky.cs.util.TimeBitmapUtil;
import com.ncuky.cs.util.TimeSlot;
import com.ncuky.cs.util.TimeSlotParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 演示与压测用的种子数据。
 * <p>
 * 只在 dev profile 下运行。所有账号口令都是 123456，用 BCrypt 加盐哈希存储——
 * 注意哈希只算一次然后复用：BCrypt 单次约 100ms，三千个学生逐个算要五分钟。
 * 真实系统里每个用户的口令各不相同，不存在这个问题。
 */
@Component
@Profile("dev")
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private static final String TERM = "2026-2027-2";
    private static final String PREV_TERM = "2026-2027-1";
    private static final String[] COURSE_NAMES = {
            "数据结构", "计算机网络", "数据库原理", "操作系统", "编译原理", "软件工程",
            "算法设计与分析", "计算机组成原理", "人工智能导论", "分布式系统",
            "信息安全基础", "Web 开发实践"
    };

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    @Value("${app.seed.students:3000}")
    private int studentCount;

    @Value("${app.seed.classes:12}")
    private int classCount;

    /** 教师数。教学班按 i % teacherCount 轮流分配给这些教师 */
    private static final int TEACHER_COUNT = 5;

    /** seedUsers 里插入教师后回查到的 sys_user.id，供 seedTeachingClasses 填 teacher_id */
    private final List<Long> teacherIds = new ArrayList<>();

    public DataInitializer(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    @Override
    public void run(String... args) {
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class);
        if (exists != null && exists > 0) {
            log.info("种子数据已存在，跳过初始化");
            return;
        }
        long t0 = System.currentTimeMillis();

        String hash = encoder.encode("123456");
        seedUsers(hash);
        seedCourses();
        seedTeachingClasses();
        seedRound();

        log.info("种子数据初始化完成：{} 名学生 / {} 个教学班，耗时 {}ms。"
                        + "教务账号 admin，教师账号 t001~t{}，学生账号 s0001~s{}，口令统一 123456",
                studentCount, classCount, System.currentTimeMillis() - t0,
                String.format("%03d", TEACHER_COUNT), String.format("%04d", studentCount));
    }

    private void seedUsers(String hash) {
        jdbc.update("INSERT INTO sys_user(username, password_hash, real_name, role, status) "
                        + "VALUES (?,?,?,?,1)",
                "admin", hash, "教务管理员", SysUser.ROLE_ADMIN);

        List<Object[]> users = new ArrayList<>(studentCount);
        for (int i = 1; i <= studentCount; i++) {
            String no = String.format("s%04d", i);
            users.add(new Object[]{no, hash, "学生" + i, SysUser.ROLE_STUDENT});
        }
        jdbc.batchUpdate("INSERT INTO sys_user(username, password_hash, real_name, role, status) "
                + "VALUES (?,?,?,?,1)", users);

        // 教师账号。放在学生之后插入，这样学生的 user_id 仍然是连续的 2..studentCount+1，
        // 下面按 i + 1 推算 user_id 的写法不用改。
        List<Object[]> teachers = new ArrayList<>(TEACHER_COUNT);
        for (int i = 1; i <= TEACHER_COUNT; i++) {
            teachers.add(new Object[]{String.format("t%03d", i), hash, "教师" + i, SysUser.ROLE_TEACHER});
        }
        jdbc.batchUpdate("INSERT INTO sys_user(username, password_hash, real_name, role, status) "
                + "VALUES (?,?,?,?,1)", teachers);
        teacherIds.addAll(jdbc.queryForList(
                "SELECT id FROM sys_user WHERE role = ? ORDER BY id", Long.class, SysUser.ROLE_TEACHER));

        // 学生表。user_id 从 2 开始，1 号是 admin
        List<Object[]> students = new ArrayList<>(studentCount);
        for (int i = 1; i <= studentCount; i++) {
            long userId = i + 1L;
            String no = String.format("2023%05d", i);
            long majorId = (i % 3) + 1;          // 三个专业轮着来
            int grade = 2023;
            students.add(new Object[]{userId, no, majorId, grade,
                    "计算机" + ((i % 6) + 1) + "班", 0.0, 30.0});
        }
        jdbc.batchUpdate("INSERT INTO student(user_id, student_no, major_id, grade, class_name, "
                + "credit_earned, credit_limit) VALUES (?,?,?,?,?,?,?)", students);
    }

    private void seedCourses() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < COURSE_NAMES.length; i++) {
            // 「编译原理」以「数据结构」为先修课，用来演示先修校验
            String prereq = "编译原理".equals(COURSE_NAMES[i]) ? "[1]" : null;
            rows.add(new Object[]{
                    "CS" + String.format("%03d", i + 1),
                    COURSE_NAMES[i],
                    2.0 + (i % 3),              // 2~4 学分
                    32 + (i % 3) * 16,
                    (i % 3) + 1,
                    prereq, 1L});
        }
        jdbc.batchUpdate("INSERT INTO course(course_no, name, credit, hours, nature, "
                + "prerequisite_ids, dept_id) VALUES (?,?,?,?,?,?,?)", rows);
    }

    private void seedTeachingClasses() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < classCount; i++) {
            long courseId = (i % COURSE_NAMES.length) + 1L;

            // 两个时段：故意让相邻教学班的时间有重叠，方便演示冲突检测
            int day1 = (i % 5) + 1;
            int start1 = (i % 3) * 3 + 1;
            int day2 = ((i + 2) % 5) + 1;
            int start2 = ((i + 1) % 3) * 3 + 1;
            List<TimeSlot> slots = List.of(
                    new TimeSlot(day1, start1, start1 + 1),
                    new TimeSlot(day2, start2, start2 + 1));
            long[] bm = TimeBitmapUtil.encode(slots);

            // 第一个教学班容量刻意设成 50，给四方案对比压测用
            int capacity = (i == 0) ? 50 : 60 + i * 5;

            rows.add(new Object[]{
                    "CS" + String.format("%03d", (int) courseId) + "-0" + (i / COURSE_NAMES.length + 1),
                    courseId, teacherIds.get(i % TEACHER_COUNT), "教师" + ((i % TEACHER_COUNT) + 1), TERM,
                    capacity, 0, 0,
                    TimeSlotParser.toJson(slots),
                    bm[1], bm[0],
                    "理工楼A" + (301 + i), null, null, 1});
        }
        jdbc.batchUpdate("INSERT INTO teaching_class(class_no, course_id, teacher_id, teacher_name, "
                + "term, capacity, selected_count, version, time_slots, time_bitmap_hi, "
                + "time_bitmap_lo, location, target_majors, target_grades, status) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", rows);

        log.info("教学班已生成，其中 1 号教学班容量 50，供四方案对比压测使用");
    }

    private void seedRound() {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO selection_round(name, term, start_time, end_time, "
                        + "target_grades, target_majors, warmed_up, status) VALUES (?,?,?,?,?,?,0,1)",
                TERM + " 第一轮", TERM,
                Timestamp.valueOf(now.minusHours(1)),
                Timestamp.valueOf(now.plusDays(30)),
                null, null);
        log.info("选课轮次已创建并处于开放状态，有效期 30 天（本地演示用，省得每次改时间）");
    }
}
