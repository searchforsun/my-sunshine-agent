package com.sunshine.skill.storage;

import com.sunshine.skill.dto.SkillFileEntry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 将 Skill 包内文件打包为 zip */
public final class SkillPackageExporter {

    private SkillPackageExporter() {
    }

    public static byte[] toZip(List<SkillFileEntry> files, Function<String, byte[]> reader) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (SkillFileEntry entry : files) {
                if (entry.directory()) {
                    continue;
                }
                byte[] data = reader.apply(entry.path());
                if (data == null) {
                    throw new IOException("读取文件失败: " + entry.path());
                }
                String name = entry.path().replace('\\', '/');
                zos.putNextEntry(new ZipEntry(name));
                zos.write(data);
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }
}
