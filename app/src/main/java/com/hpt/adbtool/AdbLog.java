package com.hpt.adbtool;

/** 统一日志接口，UI 层实现彩色日志输出 */
public interface AdbLog {
    String INFO = "INFO";
    String OK = "OK";
    String WARN = "WARN";
    String ERR = "ERR";

    void log(String level, String msg);
}
