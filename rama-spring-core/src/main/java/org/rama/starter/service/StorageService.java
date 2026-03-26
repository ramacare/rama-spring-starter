package org.rama.starter.service;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import org.apache.tika.Tika;
import org.rama.starter.entity.asset.AssetFile;
import org.rama.starter.repository.asset.AssetFileRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Year;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

public class StorageService {
    private final String fileStoragePath;
    private final String fileStorageLocation;
    private final AssetFileRepository assetFileRepository;
    private final MinioClient minioClient;
    private final Tika tika = new Tika();

    public StorageService(String fileStoragePath, String fileStorageLocation, AssetFileRepository assetFileRepository, ObjectProvider<MinioClient> minioClientProvider) {
        this.fileStoragePath = fileStoragePath;
        this.fileStorageLocation = fileStorageLocation;
        this.assetFileRepository = assetFileRepository;
        this.minioClient = minioClientProvider.getIfAvailable();
    }

    public AssetFile storeBase64(String base64Data, String bucketName, String fileName) throws Exception {
        byte[] bytes = decodeBase64Payload(base64Data);
        return store(bytes, fileName, tika.detect(bytes), fileStorageLocation, bucketName, true);
    }

    public AssetFile storeBytes(byte[] bytes, String bucketName, String fileName) throws Exception {
        return store(bytes, fileName, tika.detect(bytes), fileStorageLocation, bucketName, true);
    }

    public Resource retrieve(AssetFile assetFile) {
        try {
            return retrieve(assetFile.getLocation(), assetFile.getBucketName(), assetFile.getFileName());
        } catch (Exception ex) {
            throw new IllegalStateException("Could not retrieve asset " + assetFile.getId(), ex);
        }
    }

    public Resource retrieve(Long assetId) {
        return assetFileRepository.findById(assetId).map(this::retrieve).orElseThrow(() -> new IllegalStateException("Asset not found"));
    }

    public Resource retrieve(String location, String bucketName, String fileName) throws Exception {
        if ("s3".equalsIgnoreCase(location)) {
            ensureS3();
            try (InputStream inputStream = minioClient.getObject(
                    GetObjectArgs.builder().bucket(bucketName).object(buildObjectKey(fileName)).build())) {
                return new ByteArrayResource(inputStream.readAllBytes());
            }
        }
        Path filePath = Paths.get(fileStoragePath, bucketName, String.valueOf(fileName.charAt(0)), String.valueOf(fileName.charAt(1)), fileName);
        return new ByteArrayResource(Files.readAllBytes(filePath));
    }

    public void rawStore(byte[] bytes, String bucketName, String fileName) throws Exception {
        rawStore(bytes, fileName, tika.detect(bytes), fileStorageLocation, bucketName);
    }

    public void rawStore(Path sourceFilePath, String fileName, String contentType, String location, String bucketName) throws Exception {
        bucketName = bucketName.replace("$", "");
        if ("s3".equalsIgnoreCase(location)) {
            ensureS3();
            if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build())) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
            }
            try (InputStream inputStream = Files.newInputStream(sourceFilePath)) {
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(bucketName)
                        .object(fileName)
                        .stream(inputStream, Files.size(sourceFilePath), -1)
                        .contentType(contentType)
                        .build());
            }
            return;
        }
        Path destinationPath = Paths.get(fileStoragePath, bucketName, fileName);
        Files.createDirectories(destinationPath.getParent());
        Files.copy(sourceFilePath, destinationPath, StandardCopyOption.REPLACE_EXISTING);
    }

    public void rawStore(byte[] bytes, String fileName, String contentType, String location, String bucketName) throws Exception {
        bucketName = bucketName.replace("$", "");
        if ("s3".equalsIgnoreCase(location)) {
            ensureS3();
            if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build())) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
            }
            try (ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes)) {
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(bucketName)
                        .object(fileName)
                        .stream(inputStream, bytes.length, -1)
                        .contentType(contentType)
                        .build());
            }
            return;
        }
        Path filePath = Paths.get(fileStoragePath, bucketName, fileName);
        Files.createDirectories(filePath.getParent());
        Files.write(filePath, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public boolean rawExists(String bucketName, String fileName) throws Exception {
        return rawExists(fileName, fileStorageLocation, bucketName);
    }

    public boolean rawExists(String fileName, String location, String bucketName) throws Exception {
        bucketName = bucketName.replace("$", "");
        if ("s3".equalsIgnoreCase(location)) {
            ensureS3();
            try {
                minioClient.statObject(StatObjectArgs.builder().bucket(bucketName).object(fileName).build());
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }
        return Files.exists(Paths.get(fileStoragePath, bucketName, fileName));
    }

    public byte[] rawRetrieve(String bucketName, String fileName) throws Exception {
        return rawRetrieve(fileName, fileStorageLocation, bucketName);
    }

    public byte[] rawRetrieve(String fileName, String location, String bucketName) throws Exception {
        bucketName = bucketName.replace("$", "");
        if ("s3".equalsIgnoreCase(location)) {
            ensureS3();
            try (InputStream inputStream = minioClient.getObject(GetObjectArgs.builder().bucket(bucketName).object(fileName).build())) {
                return inputStream.readAllBytes();
            }
        }
        return Files.readAllBytes(Paths.get(fileStoragePath, bucketName, fileName));
    }

    public String getBase64Content(AssetFile assetFile) {
        try {
            return Base64.getEncoder().encodeToString(retrieve(assetFile).getContentAsByteArray());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read file content as base64", ex);
        }
    }

    public String calculateMD5hash(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return bytesToHex(md.digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("Could not calculate MD5 hash", ex);
        }
    }

    private AssetFile store(byte[] bytes, String originalFileName, String contentType, String location, String bucketName, boolean insertIfDuplicate) throws Exception {
        String normalizedBucket = normalizeBucketName(bucketName);
        String md5hash = calculateMD5hash(bytes);
        if (!insertIfDuplicate) {
            AssetFile existing = assetFileRepository.findFirstByLocationAndBucketNameAndMd5hash(location, normalizedBucket, md5hash);
            if (existing != null) {
                return existing;
            }
        }

        String uniqueFileName = UUID.randomUUID().toString().replace("-", "");
        String objectKey = buildObjectKey(uniqueFileName);
        rawStore(bytes, objectKey, contentType, location, normalizedBucket);

        AssetFile assetFile = new AssetFile();
        assetFile.setFileName(uniqueFileName);
        assetFile.setFileType(contentType);
        assetFile.setMd5hash(md5hash);
        assetFile.setOriginalFileName(originalFileName);
        assetFile.setLocation(location);
        assetFile.setBucketName(normalizedBucket);
        return assetFileRepository.save(assetFile);
    }

    private byte[] decodeBase64Payload(String base64Data) {
        String payload = base64Data != null && base64Data.contains("base64,") ? base64Data.split(",", 2)[1] : base64Data;
        return Base64.getDecoder().decode(payload);
    }

    private String normalizeBucketName(String bucketName) {
        String value = bucketName == null ? "" : bucketName;
        boolean addYearPrefix = !value.endsWith("$");
        value = value.replace("$", "");
        return addYearPrefix ? Year.now() + "-" + value : value;
    }

    private String buildObjectKey(String uniqueFileName) {
        return uniqueFileName.charAt(0) + "/" + uniqueFileName.charAt(1) + "/" + uniqueFileName;
    }

    private void ensureS3() {
        if (minioClient == null) {
            throw new IllegalStateException("MinioClient is not configured");
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }
}
