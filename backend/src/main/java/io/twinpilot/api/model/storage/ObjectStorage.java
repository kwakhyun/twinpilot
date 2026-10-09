package io.twinpilot.api.model.storage;

import java.io.InputStream;

/** 3D 모델 파일 저장소. 로컬 파일시스템 또는 S3 호환(NCP Object Storage). */
public interface ObjectStorage {

    void put(String key, byte[] data, String contentType);

    /** 큰 파일을 메모리에 올리지 않고 스트림으로 저장한다. */
    void put(String key, InputStream data, long length, String contentType);

    InputStream get(String key);

    void delete(String key);

    String kind();
}
