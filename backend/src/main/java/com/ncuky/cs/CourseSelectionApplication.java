package com.ncuky.cs;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 基于缓存与消息队列的高校选课系统
 * <p>
 * 2027 届本科毕业设计。
 */
@SpringBootApplication
@MapperScan("com.ncuky.cs.mapper")
@EnableScheduling
public class CourseSelectionApplication {

    public static void main(String[] args) {
        SpringApplication.run(CourseSelectionApplication.class, args);
    }
}
