package org.rama.controller.asset;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rama.entity.asset.AssetFile;
import org.rama.repository.asset.AssetFileRepository;
import org.rama.service.StorageService;
import org.rama.util.EncryptionUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.Optional;

@Controller
@RequiredArgsConstructor
@Slf4j
public class AssetFileController {
    @Autowired
    private StorageService storageService;

    @Autowired
    private AssetFileRepository assetFileRepository;

    @PostMapping("/upload")
    public ResponseEntity<?> storeFile(@RequestParam("file") MultipartFile file,
                                       @RequestParam("location") String location,
                                       @RequestParam("bucketName") String bucketName) {
        try {
            AssetFile assetFile = storageService.storeFile(file, location, bucketName);
            return new ResponseEntity<>(assetFile, HttpStatus.CREATED);
        } catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/asset/{token}")
    public ResponseEntity<Resource> retrieve(@PathVariable("token") String token) {
        try {
            DecodedAssetToken decodedToken = decodeAssetToken(token);
            if (decodedToken == null) {
                return new ResponseEntity<>(HttpStatus.FORBIDDEN);
            }
            Optional<AssetFile> assetFile = assetFileRepository.findById(decodedToken.assetId);
            if (assetFile.isPresent()) {
                Resource resource = storageService.retrieve(assetFile.get());
                String downloadName = assetFile.get().getOriginalFileName();
                if (downloadName == null || downloadName.isBlank()) {
                    downloadName = assetFile.get().getFileName();
                }
                String encodedName = java.net.URLEncoder.encode(downloadName, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");

                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(assetFile.get().getFileType()))
                        .header("Content-Disposition", "inline; filename=\"" + downloadName + "\"; filename*=UTF-8''" + encodedName)
                        .body(resource);
            } else {
                return new ResponseEntity<>(HttpStatus.NOT_FOUND);
            }
        } catch (Exception e) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }

    @MutationMapping(name = "addAssetFileFromBase64")
    public AssetFile addAssetFileFromBase64(@Argument String base64String, @Argument Optional<String> bucketName) {
        try {
            return storageService.storeBase64(base64String, bucketName.orElse("default"));
        } catch (Exception e) {
            return null;
        }
    }

    @SchemaMapping(typeName = "AssetFile", field = "base64String")
    public String getBase64String(AssetFile assetFile) {
        try {
            return storageService.getBase64Content(assetFile);
        } catch (Exception e) {
            return null;
        }
    }

    private DecodedAssetToken decodeAssetToken(String token) {
        String tokenDecoded = EncryptionUtil.base64UrlDecodeToString(token);
        if (tokenDecoded == null) {
            return null;
        }
        String[] tokenParts = tokenDecoded.split("\\|", 2);
        if (tokenParts.length != 2) {
            return null;
        }
        String key = tokenParts[0];
        String encryptedPayload = tokenParts[1];
        String encryptedPayloadDecoded = EncryptionUtil.base64UrlDecodeToString(encryptedPayload);
        if (encryptedPayloadDecoded == null) {
            return null;
        }
        String payload = EncryptionUtil.xorObfuscate(encryptedPayloadDecoded, key);
        if (payload == null) {
            return null;
        }
        String[] payloadParts = payload.split("\\|", 2);
        if (payloadParts.length != 2) {
            return null;
        }
        try {
            long tokenTimeMs = Long.parseLong(payloadParts[0]);
            long nowMs = Instant.now().toEpochMilli();
            if (nowMs > tokenTimeMs + 60L * 60L * 1000L) {
                return null;
            }
            Long assetId = Long.parseLong(payloadParts[1]);
            return new DecodedAssetToken(tokenTimeMs, assetId);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private record DecodedAssetToken(long tokenTimeMs, Long assetId) {}
}
