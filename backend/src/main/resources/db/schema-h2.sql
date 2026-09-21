-- H2 建表脚本（dev profile 专用，MySQL 兼容模式）
-- 结构与 sql/schema.sql 保持一致，只做 H2 语法适配：
--   JSON            -> VARCHAR
--   BIGINT UNSIGNED -> BIGINT（位图高位只用到低 20 位，不会为负）
--   DATETIME        -> TIMESTAMP

DROP TABLE IF EXISTS sys_user;
CREATE TABLE sys_user (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  username      VARCHAR(64)  NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL,
  real_name     VARCHAR(32)  NOT NULL,
  role          TINYINT      NOT NULL,
  status        TINYINT      NOT NULL DEFAULT 1,
  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

DROP TABLE IF EXISTS student;
CREATE TABLE student (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id       BIGINT       NOT NULL,
  student_no    VARCHAR(32)  NOT NULL UNIQUE,
  major_id      BIGINT       NOT NULL,
  grade         SMALLINT     NOT NULL,
  class_name    VARCHAR(64),
  credit_earned DECIMAL(5,1) NOT NULL DEFAULT 0,
  credit_limit  DECIMAL(5,1) NOT NULL DEFAULT 30
);
CREATE INDEX idx_student_user ON student(user_id);

DROP TABLE IF EXISTS course;
CREATE TABLE course (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  course_no        VARCHAR(32)  NOT NULL UNIQUE,
  name             VARCHAR(128) NOT NULL,
  credit           DECIMAL(3,1) NOT NULL,
  hours            SMALLINT     NOT NULL,
  nature           TINYINT      NOT NULL,
  prerequisite_ids VARCHAR(255),
  dept_id          BIGINT
);

DROP TABLE IF EXISTS teaching_class;
CREATE TABLE teaching_class (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  class_no       VARCHAR(32)  NOT NULL,
  course_id      BIGINT       NOT NULL,
  teacher_id     BIGINT       NOT NULL,
  teacher_name   VARCHAR(32),
  term           VARCHAR(16)  NOT NULL,
  capacity       INT          NOT NULL,
  selected_count INT          NOT NULL DEFAULT 0,
  version        INT          NOT NULL DEFAULT 0,
  time_slots     VARCHAR(500) NOT NULL,
  time_bitmap_hi BIGINT       NOT NULL DEFAULT 0,
  time_bitmap_lo BIGINT       NOT NULL DEFAULT 0,
  location       VARCHAR(64),
  target_majors  VARCHAR(255),
  target_grades  VARCHAR(255),
  status         TINYINT      NOT NULL DEFAULT 1
);
CREATE INDEX idx_tc_term ON teaching_class(term, status);

DROP TABLE IF EXISTS selection_round;
CREATE TABLE selection_round (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  name          VARCHAR(64) NOT NULL,
  term          VARCHAR(16) NOT NULL,
  start_time    TIMESTAMP   NOT NULL,
  end_time      TIMESTAMP   NOT NULL,
  target_grades VARCHAR(255),
  target_majors VARCHAR(255),
  warmed_up     TINYINT     NOT NULL DEFAULT 0,
  status        TINYINT     NOT NULL DEFAULT 0
);

DROP TABLE IF EXISTS course_selection;
CREATE TABLE course_selection (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  student_id        BIGINT    NOT NULL,
  teaching_class_id BIGINT    NOT NULL,
  round_id          BIGINT    NOT NULL,
  status            TINYINT   NOT NULL DEFAULT 1,
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_stu_class UNIQUE (student_id, teaching_class_id)
);
CREATE INDEX idx_cs_class ON course_selection(teaching_class_id, status);
CREATE INDEX idx_cs_student ON course_selection(student_id, status);

DROP TABLE IF EXISTS waiting_queue;
CREATE TABLE waiting_queue (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  teaching_class_id BIGINT    NOT NULL,
  student_id        BIGINT    NOT NULL,
  seq               INT       NOT NULL,
  status            TINYINT   NOT NULL DEFAULT 0,
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_wq_class_stu UNIQUE (teaching_class_id, student_id)
);
CREATE INDEX idx_wq_class_seq ON waiting_queue(teaching_class_id, status, seq);

DROP TABLE IF EXISTS local_message;
CREATE TABLE local_message (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  biz_key       VARCHAR(64)  NOT NULL UNIQUE,
  payload       VARCHAR(1000) NOT NULL,
  status        TINYINT      NOT NULL DEFAULT 0,
  retry_count   TINYINT      NOT NULL DEFAULT 0,
  next_retry_at TIMESTAMP,
  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_lm_status ON local_message(status, next_retry_at);
