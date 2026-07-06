package com.sunshine.rag.admin.catalog.parser;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.rag.admin.catalog.DocumentSourceType;
import com.sunshine.rag.exception.RagErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 按文档 sourceType 解析上传文件为 Markdown 正文 */
@Component
@RequiredArgsConstructor
public class DocumentFileParser {

    private final PdfDocumentParser pdfDocumentParser;
    private final DocxDocumentParser docxDocumentParser;

    public String parse(DocumentSourceType sourceType, MultipartFile file) {
        sourceType.validateUploadFileName(file.getOriginalFilename());
        return switch (sourceType) {
            case MARKDOWN, TEXT -> readText(file);
            case PDF -> parsePdf(file);
            case DOCX -> parseDocx(file);
        };
    }

    public String parseBytes(
            DocumentSourceType sourceType, byte[] bytes, String fileName, ParseProgressListener progress) {
        sourceType.validateUploadFileName(fileName);
        return switch (sourceType) {
            case MARKDOWN, TEXT -> readTextBytes(bytes);
            case PDF -> parsePdfBytes(bytes, progress);
            case DOCX -> parseDocxBytes(bytes, progress);
        };
    }

    public boolean isAsyncSourceType(DocumentSourceType sourceType) {
        return sourceType == DocumentSourceType.PDF || sourceType == DocumentSourceType.DOCX;
    }

    private String parsePdf(MultipartFile file) {
        try {
            return requireText(pdfDocumentParser.parse(file));
        } catch (IOException e) {
            throw new BizException(RagErrorCode.INGEST_PARSE_FAILED);
        }
    }

    private String parsePdfBytes(byte[] bytes, ParseProgressListener progress) {
        return requireText(pdfDocumentParser.parseBytes(bytes, progress));
    }

    private String parseDocx(MultipartFile file) {
        try {
            return requireText(docxDocumentParser.parse(file.getBytes()));
        } catch (IOException e) {
            throw new BizException(RagErrorCode.INGEST_PARSE_FAILED);
        }
    }

    private String parseDocxBytes(byte[] bytes, ParseProgressListener progress) {
        return requireText(docxDocumentParser.parse(bytes, progress));
    }

    private static String readText(MultipartFile file) {
        try {
            return readTextBytes(file.getBytes());
        } catch (IOException e) {
            throw new BizException(RagErrorCode.INGEST_PARSE_FAILED);
        }
    }

    private static String readTextBytes(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.isBlank()) {
            throw new BizException(RagErrorCode.CONTENT_EMPTY);
        }
        return text;
    }

    private static String requireText(String markdown) {
        if (!StringUtils.hasText(markdown)) {
            throw new BizException(RagErrorCode.INGEST_PARSE_FAILED);
        }
        return markdown.strip();
    }
}
