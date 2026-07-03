package com.sunshine.rag.repository;

import com.sunshine.rag.entity.KnowledgeBaseEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://ecs4c16g:3306/sunshine_rag?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8",
        "spring.datasource.username=root",
        "spring.datasource.password=root123",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=false"
})
class KnowledgeBaseRepositoryTest {

    @Autowired
    private KnowledgeBaseRepository repository;

    @Test
    void dockerInitSeedDefaultKbExists() {
        Optional<KnowledgeBaseEntity> kb = repository.findByTenantIdAndKbId("default", "default");
        assertThat(kb).isPresent();
        assertThat(kb.get().isDefault()).isTrue();
        assertThat(kb.get().getDisplayName()).isEqualTo("默认知识库");
    }
}
