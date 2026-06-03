package com.zzp.aiagent.repository.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.aiagent.exception.BusinessException;
import com.zzp.aiagent.exception.ErrorCode;
import com.zzp.aiagent.model.entity.GalleryPicture;
import com.zzp.aiagent.model.enums.StorageLocation;
import com.zzp.aiagent.repository.GalleryPictureRepository;
import lombok.extern.slf4j.Slf4j;
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
@Profile("!test")
@Slf4j
public class PostgresGalleryPictureRepository implements GalleryPictureRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    private final RowMapper<GalleryPicture> ROW_MAPPER;

    public PostgresGalleryPictureRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.ROW_MAPPER = (rs, rowNum) -> {
        List<String> tags = Collections.emptyList();
        String tagsJson = rs.getString("tags");
        if (tagsJson != null && !tagsJson.isBlank()) {
            try {
                tags = mapper.readValue(tagsJson, new TypeReference<List<String>>() {});
            } catch (Exception e) {
                log.error("[PostgresRepo] tags JSON解析失败 id={}: {}", rs.getLong("id"), e.getMessage());
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
                rs.getString("storage_location"),
                rs.getString("pic_hash")
            );
        };
    }

    @Override
    public GalleryPicture save(GalleryPicture picture) {
        if (picture.id() != null) {
            return update(picture);
        }
        String sql = """
                INSERT INTO gallery_picture (url, thumbnail_url,
                    name, introduction, category, tags, pic_size, pic_width, pic_height, pic_scale,
                    pic_format, user_id, space_id, review_status, pic_color, source_type, favorited,
                    storage_location, pic_hash)
                VALUES (:url, :thumbnailUrl,
                    :name, :introduction, :category, :tags::jsonb, :picSize, :picWidth, :picHeight, :picScale,
                    :picFormat, :userId, :spaceId, :reviewStatus, :picColor, :sourceType, :favorited,
                    :storageLocation, :picHash)
                """;
        String tagsJson;
        try {
            tagsJson = mapper.writeValueAsString(picture.tags());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.GALLERY_OPERATION_FAILED, "tags JSON序列化失败: " + e.getMessage());
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
        String tagsJson;
        try {
            tagsJson = mapper.writeValueAsString(picture.tags());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.GALLERY_OPERATION_FAILED, "tags JSON序列化失败: " + e.getMessage());
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
    public List<GalleryPicture> findByHash(String picHash) {
        if (picHash == null || picHash.isBlank()) return Collections.emptyList();
        return jdbc.query(
                "SELECT * FROM gallery_picture WHERE pic_hash=:picHash AND is_delete=0",
                new MapSqlParameterSource("picHash", picHash), ROW_MAPPER);
    }

    @Override
    public List<GalleryPicture> searchByKeyword(String query, int limit) {
        if (query == null || query.isBlank()) return Collections.emptyList();
        String sql = """
                SELECT * FROM gallery_picture
                WHERE is_delete = 0
                  AND (name ILIKE :likeQuery
                       OR introduction ILIKE :likeQuery
                       OR tags::text ILIKE :likeQuery)
                ORDER BY create_time DESC
                LIMIT :limit
                """;
        String likeQuery = "%" + query + "%";
        return jdbc.query(sql,
                new MapSqlParameterSource("likeQuery", likeQuery).addValue("limit", limit),
                ROW_MAPPER);
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

    @Override
    public List<GalleryPicture> findAllPaged(int offset, int limit, String keyword, String category,
                                              List<String> tags, Boolean favoritedOnly, String sourceType) {
        WhereClause wc = buildWhereClause(keyword, category, tags, favoritedOnly, sourceType);
        String sql = "SELECT * FROM gallery_picture" + wc.sql()
                + " ORDER BY create_time DESC LIMIT :limit OFFSET :offset";
        wc.params().addValue("limit", limit).addValue("offset", offset);
        return jdbc.query(sql, wc.params(), ROW_MAPPER);
    }

    @Override
    public int countFiltered(String keyword, String category, List<String> tags,
                              Boolean favoritedOnly, String sourceType) {
        WhereClause wc = buildWhereClause(keyword, category, tags, favoritedOnly, sourceType);
        String sql = "SELECT COUNT(*) FROM gallery_picture" + wc.sql();
        Integer result = jdbc.queryForObject(sql, wc.params(), Integer.class);
        return result != null ? result : 0;
    }

    private record WhereClause(String sql, MapSqlParameterSource params) {}

    private WhereClause buildWhereClause(String keyword, String category, List<String> tags,
                                          Boolean favoritedOnly, String sourceType) {
        StringBuilder where = new StringBuilder(" WHERE is_delete = 0");
        MapSqlParameterSource params = new MapSqlParameterSource();

        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (name ILIKE :keywordLike OR introduction ILIKE :keywordLike)");
            params.addValue("keywordLike", "%" + keyword + "%");
        }
        if (category != null && !category.isBlank()) {
            where.append(" AND category = :category");
            params.addValue("category", category);
        }
        if (tags != null && !tags.isEmpty()) {
            where.append(" AND tags::jsonb ?| ARRAY[");
            for (int i = 0; i < tags.size(); i++) {
                if (i > 0) where.append(", ");
                where.append(":tag").append(i);
                params.addValue("tag" + i, tags.get(i));
            }
            where.append("]");
        }
        if (favoritedOnly != null && favoritedOnly) {
            where.append(" AND favorited = TRUE");
        }
        if (sourceType != null && !sourceType.isBlank()) {
            where.append(" AND source_type = :sourceType");
            params.addValue("sourceType", sourceType);
        }

        return new WhereClause(where.toString(), params);
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
                .addValue("storageLocation", p.storageLocation())
                .addValue("picHash", p.picHash());
    }
}
