package com.sunshine.common.sandbox;

/** 文本文件内容（支持分片懒加载） */
public record FsContentDto(String path, String content, boolean truncated, boolean binary,
                           int offset, long totalSize) {

    /** 是否有更多内容可加载 */
    public boolean hasMore() {
        return truncated && offset + (content != null ? content.length() : 0) < totalSize;
    }

    /** 下一段的起始 offset */
    public int nextOffset() {
        return offset + (content != null ? content.length() : 0);
    }
}
