-- Add UNIQUE constraint on pic_hash to prevent duplicate uploads.
-- If the table already has duplicate hashes, this migration will fail.
-- In that case, manually deduplicate first:
--   DELETE FROM gallery_picture WHERE id NOT IN (
--     SELECT min(id) FROM gallery_picture GROUP BY pic_hash HAVING pic_hash IS NOT NULL
--   ) AND pic_hash IS NOT NULL;
ALTER TABLE gallery_picture ADD CONSTRAINT uq_gallery_pic_hash UNIQUE (pic_hash);
