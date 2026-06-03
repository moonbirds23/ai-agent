package com.zzp.aiagent.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class VectorStoreConfig {

    @Value("${spring.ai.vectorstore.pgvector.dimensions:1024}")
    private int dimensions;

    @Value("${spring.ai.vectorstore.pgvector.initialize-schema:true}")
    private boolean initializeSchema;

    @Bean
    @Profile("!test")
    VectorStore pgVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(this.dimensions)
                .initializeSchema(this.initializeSchema)
                .build();
    }
}
