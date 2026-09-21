package com.ncuky.cs.common;

/** 统一响应包装。 */
public class R<T> {

    private int code;
    private String msg;
    private T data;

    public R() {
    }

    public R(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public static <T> R<T> ok() {
        return new R<>(ResultCode.OK.code(), ResultCode.OK.msg(), null);
    }

    public static <T> R<T> ok(T data) {
        return new R<>(ResultCode.OK.code(), ResultCode.OK.msg(), data);
    }

    public static <T> R<T> of(ResultCode rc) {
        return new R<>(rc.code(), rc.msg(), null);
    }

    public static <T> R<T> of(ResultCode rc, T data) {
        return new R<>(rc.code(), rc.msg(), data);
    }

    public static <T> R<T> fail(ResultCode rc, String msg) {
        return new R<>(rc.code(), msg, null);
    }

    public boolean isOk() {
        return code == ResultCode.OK.code();
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
