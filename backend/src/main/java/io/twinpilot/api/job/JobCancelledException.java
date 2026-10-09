package io.twinpilot.api.job;

/** 사용자가 취소한 Job을 실행 중단한다. */
public class JobCancelledException extends RuntimeException {

    public JobCancelledException() {
        super("사용자가 취소했습니다.");
    }
}
