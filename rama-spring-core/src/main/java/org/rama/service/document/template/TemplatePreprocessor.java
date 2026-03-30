package org.rama.service.document.template;

import org.rama.entity.asset.AssetFile;

import java.io.InputStream;

public class TemplatePreprocessor {
    private final DocxTemplatePreprocessor docxTemplatePreprocessor;

    public TemplatePreprocessor(DocxTemplatePreprocessor docxTemplatePreprocessor) {
        this.docxTemplatePreprocessor = docxTemplatePreprocessor;
    }

    public InputStream retrieveAndPreprocess(AssetFile assetFile) throws Exception {
        if (assetFile.getOriginalFileName()!=null && assetFile.getOriginalFileName().endsWith("docx")) return docxTemplatePreprocessor.preprocess(assetFile);
        //default with word processor
        return docxTemplatePreprocessor.preprocess(assetFile);
    }

    public InputStream retrieveAndPreprocess(InputStream inputStream) throws Exception {
        return docxTemplatePreprocessor.preprocess(inputStream);
    }
}
