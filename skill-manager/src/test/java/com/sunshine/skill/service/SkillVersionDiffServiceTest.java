package com.sunshine.skill.service;

import com.sunshine.skill.dto.SkillFileContent;
import com.sunshine.skill.dto.SkillVersionDiffResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillVersionDiffServiceTest {

    @Mock
    private SkillFileService skillFileService;

    @InjectMocks
    private SkillVersionDiffService diffService;

    @Test
    void diff_binaryFile_returnsMd5() {
        byte[] v1 = new byte[] { 1, 2, 3 };
        byte[] v2 = new byte[] { 1, 2, 4 };
        when(skillFileService.readFile("demo-full-pack", 1, "assets/pixel.png"))
                .thenReturn(new SkillFileContent(
                        "assets/pixel.png", "image/png",
                        Base64.getEncoder().encodeToString(v1), true));
        when(skillFileService.readFile("demo-full-pack", 2, "assets/pixel.png"))
                .thenReturn(new SkillFileContent(
                        "assets/pixel.png", "image/png",
                        Base64.getEncoder().encodeToString(v2), true));

        SkillVersionDiffResponse resp = diffService.diff("demo-full-pack", 1, 2, "assets/pixel.png");

        assertThat(resp.binary()).isTrue();
        assertThat(resp.fromMd5()).isNotBlank();
        assertThat(resp.toMd5()).isNotBlank();
        assertThat(resp.fromMd5()).isNotEqualTo(resp.toMd5());
        assertThat(resp.lines()).isEmpty();
    }
}
