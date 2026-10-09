package io.twinpilot.api.job;

/** 장시간 작업의 진행률 보고. Job 취소가 요청되면 {@link JobCancelledException}을 던진다. */
@FunctionalInterface
public interface Progress {

    Progress NONE = (percent, message) -> { };

    void report(int percent, String message);
}
