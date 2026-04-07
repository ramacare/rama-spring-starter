package org.rama.service;

import io.minio.*;
import org.apache.tika.Tika;
import org.rama.entity.asset.AssetFile;
import org.rama.repository.asset.AssetFileRepository;
import org.rama.service.document.ImageService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Year;
import java.util.Base64;
import java.util.UUID;

public class StorageService implements StorageProvider {
    private final String fileStoragePath;
    private final String fileStorageLocation;
    private final AssetFileRepository assetFileRepository;
    private final MinioClient minioClient;
    private final ImageService imageService;
    private final Tika tika = new Tika();

    public StorageService(String fileStoragePath, String fileStorageLocation,
                          AssetFileRepository assetFileRepository,
                          ObjectProvider<MinioClient> minioClientProvider,
                          ObjectProvider<ImageService> imageServiceProvider) {
        this.fileStoragePath = fileStoragePath;
        this.fileStorageLocation = fileStorageLocation;
        this.assetFileRepository = assetFileRepository;
        this.minioClient = minioClientProvider.getIfAvailable();
        this.imageService = imageServiceProvider.getIfAvailable();
    }

    // =========================
    // storeIfNotExists overloads
    // =========================

    public AssetFile storeIfNotExists(MultipartFile file, String bucketName) throws Exception {
        return storeFile(file, fileStorageLocation, bucketName, false);
    }

    public AssetFile storeIfNotExists(String base64Data, String bucketName) throws Exception {
        return storeIfNotExists(base64Data, fileStorageLocation, bucketName);
    }

    public AssetFile storeIfNotExists(String base64Data, String location, String bucketName) throws Exception {
        return storeBase64(base64Data, location, bucketName, null, false);
    }

    public AssetFile storeIfNotExists(InputStream inputStream, String bucketName) throws Exception {
        return storeIfNotExists(inputStream, fileStorageLocation, bucketName);
    }

    public AssetFile storeIfNotExists(InputStream inputStream, String location, String bucketName) throws Exception {
        return storeInputStream(inputStream, location, bucketName, null, false);
    }

    public AssetFile storeIfNotExists(byte[] fileBytes, String bucketName) throws Exception {
        return storeIfNotExists(fileBytes, fileStorageLocation, bucketName);
    }

    public AssetFile storeIfNotExists(byte[] fileBytes, String location, String bucketName) throws Exception {
        return storeBytes(fileBytes, location, bucketName, null, false);
    }

    // =========================
    // storeFile (MultipartFile)
    // =========================

    public AssetFile storeFile(MultipartFile file, String bucketName) throws Exception {
        return storeFile(file, fileStorageLocation, bucketName, true);
    }

    public AssetFile storeFile(MultipartFile file, String location, String bucketName) throws Exception {
        return storeFile(file, location, bucketName, true);
    }

    public AssetFile storeFile(MultipartFile file, String location, String bucketName, boolean insertIfDuplicate) throws Exception {
        byte[] fileBytes = file.getBytes();
        String fileName = file.getOriginalFilename();
        String contentType = file.getContentType();

        if (contentType == null || contentType.isBlank() || "application/octet-stream".equalsIgnoreCase(contentType)) {
            contentType = tika.detect(fileBytes);
        }

        return store(fileBytes, fileName, contentType, location, bucketName, insertIfDuplicate);
    }

    // =========================
    // storeBase64
    // =========================

    public AssetFile storeBase64(String base64Data, String bucketName) throws Exception {
        return storeBase64(base64Data, fileStorageLocation, bucketName, null, true);
    }

    public AssetFile storeBase64(String base64Data, String bucketName, String fileName) throws Exception {
        return storeBase64(base64Data, fileStorageLocation, bucketName, fileName, true);
    }

    public AssetFile storeBase64(String base64Data, String location, String bucketName, String fileName) throws Exception {
        return storeBase64(base64Data, location, bucketName, fileName, true);
    }

    public AssetFile storeBase64(String base64Data, String location, String bucketName, String fileName, boolean insertIfDuplicate) throws Exception {
        byte[] fileBytes = decodeBase64Payload(base64Data);
        String contentType = tika.detect(fileBytes);
        return store(fileBytes, fileName, contentType, location, bucketName, insertIfDuplicate);
    }

    // =========================
    // storeInputStream
    // =========================

    public AssetFile storeInputStream(InputStream inputStream, String bucketName) throws Exception {
        return storeInputStream(inputStream, fileStorageLocation, bucketName, null, true);
    }

    public AssetFile storeInputStream(InputStream inputStream, String location, String bucketName) throws Exception {
        return storeInputStream(inputStream, location, bucketName, null, true);
    }

    public AssetFile storeInputStream(InputStream inputStream, String location, String bucketName, String fileName, boolean insertIfDuplicate) throws Exception {
        try (InputStream is = inputStream) {
            byte[] bytes = is.readAllBytes();
            String contentType = tika.detect(bytes);
            return store(bytes, fileName, contentType, location, bucketName, insertIfDuplicate);
        }
    }

    // =========================
    // storeBytes
    // =========================

    public AssetFile storeBytes(byte[] fileBytes, String bucketName) throws Exception {
        return storeBytes(fileBytes, fileStorageLocation, bucketName, null, true);
    }

    public AssetFile storeBytes(byte[] fileBytes, String bucketName, String fileName) throws Exception {
        return store(fileBytes, fileName, tika.detect(fileBytes), fileStorageLocation, bucketName, true);
    }

    public AssetFile storeBytes(byte[] fileBytes, String location, String bucketName, String fileName, boolean insertIfDuplicate) throws Exception {
        String contentType = tika.detect(fileBytes);
        return store(fileBytes, fileName, contentType, location, bucketName, insertIfDuplicate);
    }

    // =========================
    // storePath
    // =========================

    public AssetFile storePath(Path filePath, String bucketName) throws Exception {
        return storePath(filePath, fileStorageLocation, bucketName, null);
    }

    public AssetFile storePath(Path filePath, String location, String bucketName) throws Exception {
        return storePath(filePath, location, bucketName, null);
    }

    public AssetFile storePath(Path filePath, String location, String bucketName, String originalFileName) throws Exception {
        String contentType = tika.detect(filePath);
        if (contentType == null || contentType.isBlank()) {
            contentType = "application/octet-stream";
        }

        String uniqueFileName = UUID.randomUUID().toString().replace("-", "");
        String normalizedBucket = normalizeBucketName(bucketName);

        String objectKey = buildObjectKey(uniqueFileName);
        rawStore(filePath, objectKey, contentType, location, normalizedBucket);

        return saveAssetFile(uniqueFileName, contentType, uniqueFileName, originalFileName, location, normalizedBucket);
    }

    // =========================
    // retrieve
    // =========================

    public Resource retrieve(AssetFile assetFile) {
        try {
            return retrieve(assetFile.getLocation(), assetFile.getBucketName(), assetFile.getFileName());
        } catch (Exception ex) {
            throw new IllegalStateException("Could not retrieve asset " + assetFile.getId(), ex);
        }
    }

    public Resource retrieve(Long assetId) {
        return assetFileRepository.findById(assetId).map(this::retrieve)
                .orElseThrow(() -> new IllegalStateException("Asset not found: " + assetId));
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

    // =========================
    // isExists
    // =========================

    public boolean isExistsBase64(String base64Data, String location, String bucketName) {
        String normalizedBucket = normalizeBucketName(bucketName);
        byte[] fileBytes = decodeBase64Payload(base64Data);
        String md5hash = calculateMD5hash(fileBytes);
        return assetFileRepository.existsByLocationAndBucketNameAndMd5hash(location, normalizedBucket, md5hash);
    }

    // =========================
    // rawStore
    // =========================

    public void rawStore(byte[] bytes, String bucketName, String fileName) throws Exception {
        rawStore(bytes, fileName, tika.detect(bytes), fileStorageLocation, bucketName);
    }

    public void rawStore(InputStream inputStream, String bucketName, String fileName) throws Exception {
        try (InputStream is = inputStream) {
            byte[] bytes = is.readAllBytes();
            String contentType = tika.detect(bytes);
            rawStore(bytes, fileName, contentType, fileStorageLocation, bucketName);
        }
    }

    public void rawStore(byte[] bytes, String fileName, String contentType, String location, String bucketName) throws Exception {
        bucketName = bucketName.replace("$", "");
        validatePathSegment(bucketName);
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

    public void rawStore(Path sourceFilePath, String fileName, String contentType, String location, String bucketName) throws Exception {
        bucketName = bucketName.replace("$", "");
        if ("s3".equalsIgnoreCase(location)) {
            ensureS3();
            if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build())) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
            }
            long fileSize = Files.size(sourceFilePath);
            try (InputStream inputStream = Files.newInputStream(sourceFilePath)) {
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(bucketName)
                        .object(fileName)
                        .stream(inputStream, fileSize, -1)
                        .contentType(contentType)
                        .build());
            }
            return;
        }
        Path destinationPath = Paths.get(fileStoragePath, bucketName, fileName);
        Files.createDirectories(destinationPath.getParent());
        Files.copy(sourceFilePath, destinationPath, StandardCopyOption.REPLACE_EXISTING);
    }

    // =========================
    // rawExists / rawRetrieve
    // =========================

    public boolean rawExists(String bucketName, String fileName) throws Exception {
        return rawExists(fileName, fileStorageLocation, bucketName);
    }

    public boolean rawExists(String fileName, String location, String bucketName) {
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
        validatePathSegment(bucketName);
        if ("s3".equalsIgnoreCase(location)) {
            ensureS3();
            try (InputStream inputStream = minioClient.getObject(GetObjectArgs.builder().bucket(bucketName).object(fileName).build())) {
                return inputStream.readAllBytes();
            }
        }
        return Files.readAllBytes(Paths.get(fileStoragePath, bucketName, fileName));
    }

    // =========================
    // getBase64Content / calculateMD5hash
    // =========================

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

    // =========================
    // Private helpers
    // =========================

    private AssetFile store(byte[] fileBytes, String originalFileName, String contentType, String location, String bucketName, boolean insertIfDuplicate) throws Exception {
        if (contentType == null || contentType.isBlank()) {
            contentType = tika.detect(fileBytes);
        }

        if (imageService != null && imageService.isContentTypeConvertible(contentType)) {
            boolean lossless = bucketName != null && bucketName.contains("lossless");
            byte[] converted = imageService.convertAuto(fileBytes, lossless);
            if (converted != null && converted.length > 0 && converted.length < fileBytes.length) {
                fileBytes = converted;
                contentType = tika.detect(fileBytes);
            }
        }

        String normalizedBucket = normalizeBucketName(bucketName);
        String md5hash = calculateMD5hash(fileBytes);

        if (!insertIfDuplicate) {
            AssetFile existing = assetFileRepository.findFirstByLocationAndBucketNameAndMd5hash(location, normalizedBucket, md5hash);
            if (existing != null) {
                return existing;
            }
        }

        String uniqueFileName = UUID.randomUUID().toString().replace("-", "");
        String objectKey = buildObjectKey(uniqueFileName);
        rawStore(fileBytes, objectKey, contentType, location, normalizedBucket);

        return saveAssetFile(uniqueFileName, contentType, md5hash, originalFileName, location, normalizedBucket);
    }

    private AssetFile saveAssetFile(String fileName, String contentType, String md5hash, String originalFileName, String location, String bucketName) {
        AssetFile assetFile = new AssetFile();
        assetFile.setFileName(fileName);
        assetFile.setFileType(contentType);
        assetFile.setMd5hash(md5hash);
        assetFile.setOriginalFileName(originalFileName);
        assetFile.setLocation(location);
        assetFile.setBucketName(bucketName);
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
        validatePathSegment(value);
        return addYearPrefix ? Year.now() + "-" + value : value;
    }

    private void validatePathSegment(String segment) {
        if (segment != null && (segment.contains("..") || segment.contains("/") || segment.contains("\\"))) {
            throw new IllegalArgumentException("Invalid path segment: path traversal characters are not allowed");
        }
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
