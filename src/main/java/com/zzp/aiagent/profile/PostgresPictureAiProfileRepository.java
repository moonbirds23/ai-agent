package com.zzp.aiagent.profile;

import com.zzp.aiagent.profile.model.PictureAiProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Component
@Profile("postgres")
@Primary
@Slf4j
public class PostgresPictureAiProfileRepository implements PictureAiProfileRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public PostgresPictureAiProfileRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<PictureAiProfile> ROW_MAPPER = (rs, rowNum) -> {
        Timestamp analyzedAt = rs.getTimestamp("analyzed_at");
        return new PictureAiProfile(
                rs.getLong("picture_id"),
                rs.getString("subject"),
                rs.getString("scene"),
                rs.getString("style"),
                rs.getString("colors"),
                rs.getString("composition"),
                rs.getString("lighting"),
                rs.getString("mood"),
                rs.getString("image_prompt"),
                rs.getString("index_text"),
                rs.getInt("vector_status"),
                analyzedAt != null ? analyzedAt.toLocalDateTime() : null
        );
    };

    @Override
    public PictureAiProfile save(PictureAiProfile profile) {
        if (findByPictureId(profile.pictureId()).isPresent()) {
            return update(profile);
        }
        String sql = """
                INSERT INTO picture_ai_profile (picture_id, subject, scene, style, colors,
                    composition, lighting, mood, image_prompt, index_text, vector_status, analyzed_at)
                VALUES (:pictureId, :subject, :scene, :style, :colors,
                    :composition, :lighting, :mood, :imagePrompt, :indexText, :vectorStatus, :analyzedAt)
                """;
        MapSqlParameterSource params = profileParams(profile);
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(sql, params, keyHolder, new String[]{"id"});
        log.debug("[PostgresProfileRepo] 保存画像 pictureId={}", profile.pictureId());
        return findByPictureId(profile.pictureId())
                .orElseThrow(() -> new RuntimeException("保存后读取失败 pictureId=" + profile.pictureId()));
    }

    private PictureAiProfile update(PictureAiProfile profile) {
        String sql = """
                UPDATE picture_ai_profile SET subject=:subject, scene=:scene, style=:style,
                    colors=:colors, composition=:composition, lighting=:lighting, mood=:mood,
                    image_prompt=:imagePrompt, index_text=:indexText, vector_status=:vectorStatus,
                    analyzed_at=:analyzedAt, update_time=now()
                WHERE picture_id=:pictureId
                """;
        jdbc.update(sql, profileParams(profile));
        log.debug("[PostgresProfileRepo] 更新画像 pictureId={}", profile.pictureId());
        return findByPictureId(profile.pictureId())
                .orElseThrow(() -> new RuntimeException("更新后读取失败 pictureId=" + profile.pictureId()));
    }

    @Override
    public Optional<PictureAiProfile> findByPictureId(Long pictureId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM picture_ai_profile WHERE picture_id=:pictureId",
                    new MapSqlParameterSource("pictureId", pictureId), ROW_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<PictureAiProfile> findByPictureIds(List<Long> pictureIds) {
        if (pictureIds == null || pictureIds.isEmpty()) return Collections.emptyList();
        return jdbc.query(
                "SELECT * FROM picture_ai_profile WHERE picture_id IN (:pictureIds)",
                new MapSqlParameterSource("pictureIds", pictureIds), ROW_MAPPER);
    }

    @Override
    public void deleteByPictureId(Long pictureId) {
        jdbc.update("DELETE FROM picture_ai_profile WHERE picture_id=:pictureId",
                new MapSqlParameterSource("pictureId", pictureId));
        log.debug("[PostgresProfileRepo] 删除画像 pictureId={}", pictureId);
    }

    private MapSqlParameterSource profileParams(PictureAiProfile p) {
        return new MapSqlParameterSource()
                .addValue("pictureId", p.pictureId())
                .addValue("subject", p.subject())
                .addValue("scene", p.scene())
                .addValue("style", p.style())
                .addValue("colors", p.colors())
                .addValue("composition", p.composition())
                .addValue("lighting", p.lighting())
                .addValue("mood", p.mood())
                .addValue("imagePrompt", p.imagePrompt())
                .addValue("indexText", p.indexText())
                .addValue("vectorStatus", p.vectorStatus())
                .addValue("analyzedAt", p.analyzedAt() != null ? Timestamp.valueOf(p.analyzedAt()) : null);
    }
}
