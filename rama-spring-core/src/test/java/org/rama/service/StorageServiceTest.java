package org.rama.service;

import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.rama.entity.asset.AssetFile;
import org.rama.repository.asset.AssetFileRepository;
import org.rama.service.document.ImageService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;
import java.time.Year;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class StorageServiceTest {

    @Mock
    private AssetFileRepository assetFileRepository;

    @Mock
    private ObjectProvider<MinioClient> minioClientProvider;

    @Mock
    private ObjectProvider<ImageService> imageServiceProvider;

    @Captor
    private ArgumentCaptor<AssetFile> assetFileCaptor;

    @TempDir
    Path tempDir;

    private StorageService storageService;

    @BeforeEach
    void setup() {
        when(minioClientProvider.getIfAvailable()).thenReturn(null);
        when(imageServiceProvider.getIfAvailable()).thenReturn(null);
        storageService = new StorageService(
                tempDir.toString(),
                "local",
                assetFileRepository,
                minioClientProvider,
                imageServiceProvider
        );
    }

    @Test
    void storeBase64_shouldDecodeAndPersist() throws Exception {
        // Arrange
        byte[] originalBytes = "hello world".getBytes();
        String base64Data = Base64.getEncoder().encodeToString(originalBytes);
        String expectedMd5 = storageService.calculateMD5hash(originalBytes);

        AssetFile savedFile = new AssetFile();
        savedFile.setFileName("test-file");
        savedFile.setMd5hash(expectedMd5);
        when(assetFileRepository.save(any(AssetFile.class))).thenReturn(savedFile);

        // Act
        AssetFile result = storageService.storeBase64(base64Data, "test-bucket");

        // Assert
        verify(assetFileRepository).save(assetFileCaptor.capture());
        AssetFile captured = assetFileCaptor.getValue();
        assertThat(captured.getMd5hash()).isEqualTo(expectedMd5);
        assertThat(captured.getLocation()).isEqualTo("local");
        assertThat(captured.getBucketName()).isEqualTo(Year.now() + "-test-bucket");
        assertThat(result).isNotNull();
        assertThat(result.getMd5hash()).isEqualTo(expectedMd5);
    }

    @Test
    void storeIfNotExists_shouldReturnExistingWhenDuplicate() throws Exception {
        // Arrange
        byte[] originalBytes = "duplicate content".getBytes();
        String base64Data = Base64.getEncoder().encodeToString(originalBytes);
        String expectedMd5 = storageService.calculateMD5hash(originalBytes);
        String normalizedBucket = Year.now() + "-test-bucket";

        AssetFile existingFile = new AssetFile();
        existingFile.setFileName("existing-file");
        existingFile.setMd5hash(expectedMd5);
        when(assetFileRepository.findFirstByLocationAndBucketNameAndMd5hash("local", normalizedBucket, expectedMd5))
                .thenReturn(existingFile);

        // Act
        AssetFile result = storageService.storeIfNotExists(base64Data, "test-bucket");

        // Assert
        assertThat(result).isSameAs(existingFile);
        verify(assetFileRepository, never()).save(any(AssetFile.class));
    }

    @Test
    void storeFile_shouldDetectContentType() throws Exception {
        // Arrange
        byte[] pngHeader = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        MockMultipartFile multipartFile = new MockMultipartFile(
                "file", "test.png", "application/octet-stream", pngHeader
        );

        AssetFile savedFile = new AssetFile();
        savedFile.setFileType("image/png");
        when(assetFileRepository.save(any(AssetFile.class))).thenReturn(savedFile);

        // Act
        AssetFile result = storageService.storeFile(multipartFile, "images");

        // Assert
        verify(assetFileRepository).save(assetFileCaptor.capture());
        AssetFile captured = assetFileCaptor.getValue();
        // Tika should detect a more specific content type than application/octet-stream
        assertThat(captured.getFileType()).isNotEqualTo("application/octet-stream");
    }

    @Test
    void calculateMD5hash_shouldReturnConsistentHash() {
        // Arrange
        byte[] bytes = "consistent content".getBytes();

        // Act
        String hash1 = storageService.calculateMD5hash(bytes);
        String hash2 = storageService.calculateMD5hash(bytes);

        // Assert
        assertThat(hash1).isNotNull();
        assertThat(hash1).isNotEmpty();
        assertThat(hash1).isEqualTo(hash2);
        // MD5 hex string is always 32 characters
        assertThat(hash1).hasSize(32);
    }

    @Test
    void normalizeBucketName_shouldAddYearPrefix() throws Exception {
        // Arrange - we verify normalization indirectly through storeBase64
        byte[] bytes = "test".getBytes();
        String base64Data = Base64.getEncoder().encodeToString(bytes);
        when(assetFileRepository.save(any(AssetFile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        AssetFile result = storageService.storeBase64(base64Data, "documents");

        // Assert - bucket name should be prefixed with current year
        verify(assetFileRepository).save(assetFileCaptor.capture());
        assertThat(assetFileCaptor.getValue().getBucketName()).isEqualTo(Year.now() + "-documents");
    }

    @Test
    void normalizeBucketName_shouldNotAddYearPrefix_whenBucketEndsWithDollarSign() throws Exception {
        // Arrange - bucket names ending with $ should not get year prefix
        byte[] bytes = "test".getBytes();
        String base64Data = Base64.getEncoder().encodeToString(bytes);
        when(assetFileRepository.save(any(AssetFile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        AssetFile result = storageService.storeBase64(base64Data, "static-bucket$");

        // Assert - bucket name should NOT be prefixed with year, and $ should be stripped
        verify(assetFileRepository).save(assetFileCaptor.capture());
        assertThat(assetFileCaptor.getValue().getBucketName()).isEqualTo("static-bucket");
    }
}
