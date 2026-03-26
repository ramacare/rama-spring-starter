package org.rama.starter.repository.asset;

import org.rama.starter.entity.asset.AssetFile;
import org.rama.starter.repository.BaseRepository;

public interface AssetFileRepository extends BaseRepository<AssetFile, Long> {
    AssetFile findFirstByLocationAndBucketNameAndMd5hash(String location, String bucketName, String md5hash);
    boolean existsByLocationAndBucketNameAndMd5hash(String location, String bucketName, String md5hash);
}
