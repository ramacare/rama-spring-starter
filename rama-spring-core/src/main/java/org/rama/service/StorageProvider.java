package org.rama.service;

import org.springframework.core.io.Resource;

public interface StorageProvider {
    Resource retrieve(Long assetId);

    void rawStore(byte[] bytes, String bucketName, String fileName) throws Exception;

    boolean rawExists(String bucketName, String fileName) throws Exception;

    byte[] rawRetrieve(String bucketName, String fileName) throws Exception;

    String calculateMD5hash(byte[] bytes);
}
