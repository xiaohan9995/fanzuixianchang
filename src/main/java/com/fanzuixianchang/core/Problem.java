package com.fanzuixianchang.core;

/** Public safe error; never contains provider bodies, credentials or user images. */
public final class Problem extends RuntimeException {
    private final String code;
    private final int status;
    public Problem(int status,String code,String message) {super(message);this.status=status;this.code=code;}
    public int status(){return status;}
    public String code(){return code;}
    public static Problem input(String message){return new Problem(400,"INVALID_INPUT",message);}
    public static Problem output(){return new Problem(502,"INVALID_MODEL_OUTPUT","本次结果未通过核对，请补充线索或重试；已有饭案不会被覆盖。");}
}
