package com.zzp.aiagent.gallery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.aiagent.gallery.model.GalleryPicture;
import com.zzp.aiagent.gallery.model.StorageLocation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Repository
@Profile("postgres")
@Primary
@Slf4j
public class PostgresGalleryPictureRepository implements GalleryPictureRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PostgresGalleryPictureRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    private static final RowMapper<GalleryPicture> ROW_MAPPER = (rs, rowNum) -> {
        List<String> tags = Collections.emptyList();
        String tagsJson = rs.getString("tags");
        if (tagsJson != null && !tagsJson.isBlank()) {
            try {
                tags = new ObjectMapper().readValue(tagsJson, new TypeReference<List<String>>() {});
            } catch (Exception ignored) {
            }
        }
        return new GalleryPicture(
                rs.getLong("id"),
                rs.getString("url"),
                rs.getString("thumbnail_url"),
                rs.getString("name"),
                rs.getString("introduction"),
                rs.getString("category"),
                tags,
                (Long) rs.getObject("pic_size"),
                (Integer) rs.getObject("pic_width"),
                (Integer) rs.getObject("pic_height"),
                (Double) rs.getObject("pic_scale"),
                rs.getString("pic_format"),
                rs.getLong("user_id"),
                rs.getLong("space_id"),
                rs.getInt("review_status"),
                rs.getString("pic_color"),
                rs.getString("source_type"),
                rs.getBoolean("favorited"),
                rs.getTimestamp("create_time") != null ? rs.getTimestamp("create_time").toLocalDateTime() : null,
                rs.getTimestamp("update_time") != null ? rs.getTimestamp("update_time").toLocalDateTime() : null,
                rs.getString("storage_location")
        );
    };

    @Override
    public GalleryPicture save(GalleryPicture picture) {
        if (picture.id() != null) {
            return update(picture);
        }
        String sql = """
                INSERT INTO gallery_picture (url, thumbnail_url,
                    name, introduction, category, tags, pic_size, pic_width, pic_height, pic_scale,
                    pic_format, user_id, space_id, review_status, pic_color, source_type, favorited,
                    storage_location)
                VALUES (:url, :thumbnailUrl,
                    :name, :introduction, :category, :tags::jsonb, :picSize, :picWidth, :picHeight, :picScale,
                    :picFormat, :userId, :spaceId, :reviewStatus, :picColor, :sourceType, :favorited,
                    :storageLocation)
                """;
        String tagsJson = null;
        try {
            tagsJson = mapper.writeValueAsString(picture.tags());
        } catch (Exception ignored) {
        }
        MapSqlParameterSource params = baseParams(picture)
                .addValue("tags", tagsJson);
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(sql, params, keyHolder, new String[]{"id"});
        long id = keyHolder.getKey().longValue();
        log.debug("[PostgresRepo] 保存图片 id={}", id);
        return findById(id).orElseThrow(() -> new RuntimeException("保存后读取失败 id=" + id));
    }

    private GalleryPicture update(GalleryPicture picture) {
        String sql = """
                UPDATE gallery_picture SET url=:url, thumbnail_url=:thumbnailUrl,
                    name=:name, introduction=:introduction,
                    category=:category, tags=:tags::jsonb, pic_size=:picSize, pic_width=:picWidth,
                    pic_height=:picHeight, pic_scale=:picScale, pic_format=:picFormat,
                    pic_color=:picColor, source_type=:sourceType, favorited=:favorited,
                    storage_location=:storageLocation, update_time=now()
                WHERE id=:id AND is_delete=0
                """;
        String tagsJson = null;
        try {
            tagsJson = mapper.writeValueAsString(picture.tags());
        } catch (Exception ignored) {
        }
        MapSqlParameterSource params = baseParams(picture).addValue("tags", tagsJson);
        jdbc.update(sql, params);
        log.debug("[PostgresRepo] 更新图片 id={}", picture.id());
        return findById(picture.id()).orElseThrow(() -> new RuntimeException("更新后读取失败 id=" + picture.id()));
    }

    @Override
    public Optional<GalleryPicture> findById(Long id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM gallery_picture WHERE id=:id AND is_delete=0",
                    new MapSqlParameterSource("id", id), ROW_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<GalleryPicture> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyList();
        return jdbc.query(
                "SELECT * FROM gallery_picture WHERE id IN (:ids) AND is_delete=0",
                new MapSqlParameterSource("ids", ids), ROW_MAPPER);
    }

    @Override
    public List<GalleryPicture> findAll() {
        return jdbc.query(
                "SELECT * FROM gallery_picture WHERE is_delete=0 ORDER BY create_time DESC",
                ROW_MAPPER);
    }

    @Override
    public void deleteById(Long id) {
        jdbc.update("UPDATE gallery_picture SET is_delete=1, update_time=now() WHERE id=:id",
                new MapSqlParameterSource("id", id));
        log.debug("[PostgresRepo] 软删除图片 id={}", id);
    }

    @Override
    public List<GalleryPicture> findExpiredCache(LocalDateTime cutoffTime) {
        String sql = """
                SELECT * FROM gallery_picture
                WHERE storage_location = 'CACHE' AND is_delete = 0
                AND create_time < :cutoffTime
                ORDER BY create_time ASC
                """;
        return jdbc.query(sql,
                new MapSqlParameterSource("cutoffTime", Timestamp.valueOf(cutoffTime)),
                ROW_MAPPER);
    }

    private MapSqlParameterSource baseParams(GalleryPicture p) {
        return new MapSqlParameterSource()
                .addValue("id", p.id())
                .addValue("url", p.url())
                .addValue("thumbnailUrl", p.thumbnailUrl())
                .addValue("name", p.name())
                .addValue("introduction", p.introduction())
                .addValue("category", p.category())
                .addValue("picSize", p.picSize())
                .addValue("picWidth", p.picWidth())
                .addValue("picHeight", p.picHeight())
                .addValue("picScale", p.picScale())
                .addValue("picFormat", p.picFormat())
                .addValue("userId", p.userId())
                .addValue("spaceId", p.spaceId())
                .addValue("reviewStatus", p.reviewStatus())
                .addValue("picColor", p.picColor())
                .addValue("sourceType", p.sourceType())
                .addValue("favorited", p.favorited())
                .addValue("storageLocation", p.storageLocation());
    }
}
