package com.ncuky.cs.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** time_slots 字段（JSON 文本）与 TimeSlot 列表之间的转换。 */
public final class TimeSlotParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TimeSlotParser() {
    }

    /** 解析 [{"day":3,"start":3,"end":4}] 这样的 JSON 文本 */
    public static List<TimeSlot> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Integer>> raw =
                    MAPPER.readValue(json, new TypeReference<List<Map<String, Integer>>>() {
                    });
            List<TimeSlot> out = new ArrayList<>(raw.size());
            for (Map<String, Integer> m : raw) {
                out.add(new TimeSlot(m.get("day"), m.get("start"), m.get("end")));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalArgumentException("上课时段 JSON 解析失败：" + json, e);
        }
    }

    public static String toJson(List<TimeSlot> slots) {
        try {
            List<Map<String, Integer>> raw = new ArrayList<>(slots.size());
            for (TimeSlot s : slots) {
                raw.add(Map.of("day", s.day(), "start", s.start(), "end", s.end()));
            }
            return MAPPER.writeValueAsString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("上课时段序列化失败", e);
        }
    }

    /** 人话描述，前端与课表直接用 */
    public static String describe(String json) {
        List<TimeSlot> slots = parse(json);
        if (slots.isEmpty()) {
            return "待定";
        }
        String[] week = {"一", "二", "三", "四", "五", "六", "日"};
        StringBuilder sb = new StringBuilder();
        for (TimeSlot s : slots) {
            if (sb.length() > 0) {
                sb.append("，");
            }
            sb.append("周").append(week[s.day() - 1])
                    .append(' ').append(s.start()).append('-').append(s.end()).append("节");
        }
        return sb.toString();
    }

    /** 解析形如 "[1,2,3]" 的 id 数组 */
    public static List<Long> parseIdArray(String json) {
        if (json == null || json.isBlank() || "null".equals(json)) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<Long>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}
