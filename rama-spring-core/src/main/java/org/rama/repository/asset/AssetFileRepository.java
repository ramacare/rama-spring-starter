package org.rama.repository.asset;

import org.rama.entity.asset.AssetFile;
import org.rama.repository.BaseRepository;
import org.springframework.graphql.data.GraphQlRepository;

@GraphQlRepository
public interface AssetFileRepository extends BaseRepository<AssetFile, Long> {
    AssetFile findFirstByLocationAndBucketNameAndMd5hash(String location, String bucketName, String md5hash);
    boolean existsByLocationAndBucketNameAndMd5hash(String location, String bucketName, String md5hash);
}
