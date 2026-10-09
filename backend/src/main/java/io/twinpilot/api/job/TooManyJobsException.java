package io.twinpilot.api.job;

/** 사용자별 대기·실행 중 Job 한도 초과(429). */
public class TooManyJobsException extends RuntimeException {

    public TooManyJobsException(String message) {
        super(message);
    }
}
