-- ============================================================
-- 高校选课系统 建表脚本
-- 毕业设计《基于缓存与消息队列的高校选课系统的设计与实现》
--
-- MySQL 8.0 / utf8mb4
-- 容器首次启动时由 docker-entrypoint-initdb.d 自动执行
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ------------------------------------------------------------
-- 1. 用户表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username`      VARCHAR(64)  NOT NULL                COMMENT '登录名',
  `password_hash` VARCHAR(100) NOT NULL                COMMENT 'BCrypt 加盐哈希，禁止明文',
  `real_name`     VARCHAR(32)  NOT NULL                COMMENT '姓名',
  `role`          TINYINT      NOT NULL                COMMENT '1学生 2教师 3教务',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '0禁用 1正常',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- ------------------------------------------------------------
-- 2. 学生表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `student`;
CREATE TABLE `student` (
  `id`            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`       BIGINT      NOT NULL                COMMENT '关联 sys_user.id',
  `student_no`    VARCHAR(32) NOT NULL                COMMENT '学号',
  `major_id`      BIGINT      NOT NULL                COMMENT '专业',
  `grade`         SMALLINT    NOT NULL                COMMENT '年级，如 2023',
  `class_name`    VARCHAR(64)          DEFAULT NULL   COMMENT '行政班',
  `credit_earned` DECIMAL(5,1) NOT NULL DEFAULT 0     COMMENT '已修学分，用于学分上限校验',
  `credit_limit`  DECIMAL(5,1) NOT NULL DEFAULT 30    COMMENT '本学期可选学分上限',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_student_no` (`student_no`),
  KEY `idx_major_grade` (`major_id`, `grade`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学生表';

-- ------------------------------------------------------------
-- 3. 课程表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `course`;
CREATE TABLE `course` (
  `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `course_no`        VARCHAR(32)  NOT NULL                COMMENT '课程代码',
  `name`             VARCHAR(128) NOT NULL                COMMENT '课程名称',
  `credit`           DECIMAL(3,1) NOT NULL                COMMENT '学分',
  `hours`            SMALLINT     NOT NULL                COMMENT '学时',
  `nature`           TINYINT      NOT NULL                COMMENT '1必修 2限选 3任选',
  `prerequisite_ids` JSON                  DEFAULT NULL   COMMENT '先修课程 id 数组，如 [12,35]',
  `dept_id`          BIGINT                DEFAULT NULL   COMMENT '开课单位',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_course_no` (`course_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程表';

-- ------------------------------------------------------------
-- 4. 教学班表
--    time_bitmap_hi / lo 是冲突检测的载体：
--    一周 7 天 × 每天 12 节 = 84 个时间槽，用两个 64 位无符号整数承载，
--    实际使用低 84 位。槽位下标 = (day - 1) * 12 + (period - 1)
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `teaching_class`;
CREATE TABLE `teaching_class` (
  `id`              BIGINT           NOT NULL AUTO_INCREMENT COMMENT '主键',
  `class_no`        VARCHAR(32)      NOT NULL                COMMENT '教学班号',
  `course_id`       BIGINT           NOT NULL                COMMENT '所属课程',
  `teacher_id`      BIGINT           NOT NULL                COMMENT '授课教师',
  `term`            VARCHAR(16)      NOT NULL                COMMENT '学期，如 2026-2027-2',
  `capacity`        INT              NOT NULL                COMMENT '容量上限，硬约束，不可突破',
  `selected_count`  INT              NOT NULL DEFAULT 0      COMMENT '已选人数，由 MQ 消费者更新',
  `time_slots`      JSON             NOT NULL                COMMENT '上课时段，如 [{"day":3,"start":3,"end":4}]',
  `time_bitmap_hi`  BIGINT UNSIGNED  NOT NULL DEFAULT 0      COMMENT '时间槽位图高 64 位',
  `time_bitmap_lo`  BIGINT UNSIGNED  NOT NULL DEFAULT 0      COMMENT '时间槽位图低 64 位',
  `location`        VARCHAR(64)               DEFAULT NULL   COMMENT '上课地点',
  `target_majors`   JSON                      DEFAULT NULL   COMMENT '限定专业 id 数组，NULL 表示不限',
  `target_grades`   JSON                      DEFAULT NULL   COMMENT '限定年级数组，NULL 表示不限',
  `status`          TINYINT          NOT NULL DEFAULT 1      COMMENT '0停开 1正常',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_class_no_term` (`class_no`, `term`),
  KEY `idx_course_term` (`course_id`, `term`),
  KEY `idx_term_status` (`term`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教学班表';

-- ------------------------------------------------------------
-- 5. 选课轮次表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `selection_round`;
CREATE TABLE `selection_round` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name`          VARCHAR(64)  NOT NULL                COMMENT '轮次名称，如 2026-2027-2 第一轮',
  `term`          VARCHAR(16)  NOT NULL                COMMENT '学期',
  `start_time`    DATETIME     NOT NULL                COMMENT '开放时间',
  `end_time`      DATETIME     NOT NULL                COMMENT '截止时间',
  `target_grades` JSON                  DEFAULT NULL   COMMENT '适用年级，NULL 表示全部',
  `target_majors` JSON                  DEFAULT NULL   COMMENT '适用专业，NULL 表示全部',
  `warmed_up`     TINYINT      NOT NULL DEFAULT 0      COMMENT '0未预热 1已预热到 Redis',
  `status`        TINYINT      NOT NULL DEFAULT 0      COMMENT '0未开始 1进行中 2已结束',
  PRIMARY KEY (`id`),
  KEY `idx_term_status` (`term`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='选课轮次表';

-- ------------------------------------------------------------
-- 6. 选课记录表
--    uk_stu_class 是消费幂等的最后一道防线：
--    即使 MQ 消息被重复消费，第二条插入也会被唯一索引拦下
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `course_selection`;
CREATE TABLE `course_selection` (
  `id`                BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
  `student_id`        BIGINT   NOT NULL                COMMENT '学生',
  `teaching_class_id` BIGINT   NOT NULL                COMMENT '教学班',
  `round_id`          BIGINT   NOT NULL                COMMENT '选课轮次',
  `status`            TINYINT  NOT NULL DEFAULT 1      COMMENT '1已选 2已退 3候补',
  `created_at`        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stu_class` (`student_id`, `teaching_class_id`),
  KEY `idx_class_status` (`teaching_class_id`, `status`),
  KEY `idx_student` (`student_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='选课记录表';

-- ------------------------------------------------------------
-- 7. 候补队列表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `waiting_queue`;
CREATE TABLE `waiting_queue` (
  `id`                BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
  `teaching_class_id` BIGINT   NOT NULL                COMMENT '教学班',
  `student_id`        BIGINT   NOT NULL                COMMENT '学生',
  `seq`               INT      NOT NULL                COMMENT '排队序号，退选后按序补位',
  `status`            TINYINT  NOT NULL DEFAULT 0      COMMENT '0排队中 1已补位 2已取消',
  `created_at`        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_class_student` (`teaching_class_id`, `student_id`),
  KEY `idx_class_seq` (`teaching_class_id`, `status`, `seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='候补队列表';

-- ------------------------------------------------------------
-- 8. 本地消息表
--    与业务同库同事务写入，配合定时重投保证消息最终送达。
--    这张表是「Redis 扣了名额但消息没投出去」这一风险的兜底。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `local_message`;
CREATE TABLE `local_message` (
  `id`            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `biz_key`       VARCHAR(64) NOT NULL                COMMENT '业务唯一键，学生id:教学班id',
  `payload`       JSON        NOT NULL                COMMENT '消息体',
  `status`        TINYINT     NOT NULL DEFAULT 0      COMMENT '0待投递 1已确认 2已失败',
  `retry_count`   TINYINT     NOT NULL DEFAULT 0      COMMENT '已重试次数',
  `next_retry_at` DATETIME             DEFAULT NULL   COMMENT '下次重试时间，指数退避',
  `created_at`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_biz_key` (`biz_key`),
  KEY `idx_status_retry` (`status`, `next_retry_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地消息表';

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================
-- 最小种子数据：一个教学班 + 若干学生，供验证脚本使用
-- 正式开发时替换为你们学校的真实课程数据
-- ============================================================
INSERT INTO `course` (`id`, `course_no`, `name`, `credit`, `hours`, `nature`)
VALUES (1001, 'CS101', '数据结构', 4.0, 64, 1);

INSERT INTO `teaching_class`
  (`id`, `class_no`, `course_id`, `teacher_id`, `term`, `capacity`, `time_slots`, `location`)
VALUES
  (1001, 'CS101-01', 1001, 1, '2026-2027-2', 50,
   '[{"day":3,"start":3,"end":4},{"day":5,"start":1,"end":2}]', '理工楼A301');

INSERT INTO `selection_round` (`id`, `name`, `term`, `start_time`, `end_time`, `status`)
VALUES (1, '2026-2027-2 第一轮', '2026-2027-2',
        '2027-01-05 12:00:00', '2027-01-08 23:59:59', 0);
