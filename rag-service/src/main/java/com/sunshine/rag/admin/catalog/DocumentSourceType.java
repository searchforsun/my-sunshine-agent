package com.sunshine.rag.admin.catalog;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.rag.exception.RagErrorCode;
import org.springframework.util.StringUtils;

/** 文档原始内容类型 — 决定占位符、可上传扩展名与是否允许在线编辑 */
public enum DocumentSourceType {
    MARKDOWN("markdown", "请上传 Markdown 文件（.md）或直接编写内容。"),
    TEXT("text", "请上传纯文本（.txt）或直接编写内容。"),
    PDF("pdf", "请上传 PDF 文件，系统将 OCR 解析为 Markdown。"),
    DOCX("docx", "请上传 Word（.docx）文件，系统将解析为 Markdown。");

    private final String wire;
    private final String placeholder;

    DocumentSourceType(String wire, String placeholder) {
        this.wire = wire;
        this.placeholder = placeholder;
    }

    public String wire() {
        return wire;
    }

    public String placeholder() {
        return placeholder;
    }

    /** 是否允许在「原始内容」区在线编辑（PDF/Word 仅上传） */
    public boolean inlineEditable() {
        return this == MARKDOWN || this == TEXT;
    }

    public static DocumentSourceType require(String raw) {
        if (!StringUtils.hasText(raw)) {
            return MARKDOWN;
        }
        for (DocumentSourceType type : values()) {
            if (type.wire.equalsIgnoreCase(raw.strip())) {
                return type;
            }
        }
        throw new BizException(RagErrorCode.DOC_SOURCE_TYPE_INVALID);
    }

    public boolean isPlaceholder(String content) {
        if (!StringUtils.hasText(content)) {
            return true;
        }
        return placeholder.equals(content.strip());
    }

    public void validateUploadFileName(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            throw new BizException(RagErrorCode.CONTENT_EMPTY);
        }
        String lower = fileName.strip().toLowerCase();
        boolean ok = switch (this) {
            case MARKDOWN -> lower.endsWith(".md") || lower.endsWith(".markdown");
            case TEXT -> lower.endsWith(".txt");
            case PDF -> lower.endsWith(".pdf");
            case DOCX -> lower.endsWith(".docx");
        };
        if (!ok) {
            throw new BizException(RagErrorCode.FILE_TYPE_NOT_SUPPORTED);
        }
    }
}
