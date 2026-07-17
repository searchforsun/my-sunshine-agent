package com.sunshine.common.sandbox;

/** 文本文件内容（超限截断） */
public record FsContentDto(String path, String content, boolean truncated, boolean binary) {
}
