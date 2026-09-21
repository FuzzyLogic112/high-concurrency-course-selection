package com.ncuky.cs.common;

/** 业务异常。带 ResultCode，由全局处理器转成统一响应。 */
public class BizException extends RuntimeException {

    private final ResultCode resultCode;

    public BizException(ResultCode rc) {
        super(rc.msg());
        this.resultCode = rc;
    }

    public BizException(ResultCode rc, String msg) {
        super(msg);
        this.resultCode = rc;
    }

    public ResultCode getResultCode() {
        return resultCode;
    }
}
