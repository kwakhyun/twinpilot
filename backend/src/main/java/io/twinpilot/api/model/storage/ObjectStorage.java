package io.twinpilot.api.model.storage;

import java.io.InputStream;

/** 3D 모델 파일 저장소. 로컬 파일시스템 또는 S3 호환(NCP Object Storage). */
public interface ObjectStorage {

    void put(String key, byte[] data, String contentType);

    InputStream get(String key);

    void delete(String key);

    String kind();
}
