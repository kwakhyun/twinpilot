package io.twinpilot.api.job;

/** 임대가 만료돼 다른 노드가 Job을 가져갔다. 결과를 쓰지 않고 조용히 멈춘다. */
public class LeaseLostException extends RuntimeException {

    public LeaseLostException(String message) {
        super(message);
    }
}
