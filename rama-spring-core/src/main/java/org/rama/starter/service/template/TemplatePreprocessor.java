package org.rama.starter.service.template;

import org.rama.starter.entity.asset.AssetFile;
import org.rama.starter.service.StorageService;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

public class TemplatePreprocessor {
    private final StorageService storageService;

    public TemplatePreprocessor(StorageService storageService) {
        this.storageService = storageService;
    }

    public InputStream retrieveAndPreprocess(AssetFile assetFile) throws Exception {
        return new ByteArrayInputStream(storageService.retrieve(assetFile).getContentAsByteArray());
    }

    public InputStream retrieveAndPreprocess(InputStream inputStream) {
        return inputStream;
    }
}
