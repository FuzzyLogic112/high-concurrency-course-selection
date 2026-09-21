package com.ncuky.cs.util;

/**
 * 一个上课时段：周几的第 start 节到第 end 节（闭区间）。
 *
 * @param day   周几，1..7
 * @param start 起始节次，1..12
 * @param end   结束节次，1..12，且不小于 start
 */
public record TimeSlot(int day, int start, int end) {

    public TimeSlot {
        if (day < 1 || day > TimeBitmapUtil.DAYS) {
            throw new IllegalArgumentException("day 必须在 1.." + TimeBitmapUtil.DAYS + "，实际 " + day);
        }
        if (start < 1 || end > TimeBitmapUtil.PERIODS || start > end) {
            throw new IllegalArgumentException(
                    "节次区间不合法：start=" + start + " end=" + end
                            + "，允许范围 1.." + TimeBitmapUtil.PERIODS);
        }
    }
}
