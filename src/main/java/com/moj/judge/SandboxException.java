package com.moj.judge;

/**
 * 沙箱基础设施异常（网络故障、连接超时、沙箱不可用等）。
 * 与业务失败（编译错误、WA、TLE 等）区分，由 JudgeConsumer 决定是否重试。
 */
public class SandboxException extends RuntimeException {
    public SandboxException(String message, Throwable cause) {
        super(message, cause);
    }
}
