-- SAFE READ-ONLY CHECK. Paste in Supabase SQL Editor; no rows are altered.
-- Expired correction requests still awaiting resubmission:
SELECT
  r.listing_id,
  l.title,
  l.listing_status,
  l.archive_reason,
  r.review_state,
  r.delete_after,
  COALESCE(array_length(l.image_paths, 1), 0) AS linked_photos
FROM public.pelak_listing_rejections AS r
JOIN public.listings AS l ON l.id = r.listing_id
WHERE r.review_state = 'needs_revision'
  AND r.delete_after IS NOT NULL
  AND r.delete_after <= now()
ORDER BY r.delete_after ASC
LIMIT 100;

-- Foreign keys that could stop safe permanent deletion:
SELECT
  con.conrelid::regclass::text AS referencing_table,
  con.conname AS foreign_key,
  CASE con.confdeltype
    WHEN 'c' THEN 'CASCADE'
    WHEN 'a' THEN 'NO ACTION'
    WHEN 'r' THEN 'RESTRICT'
    WHEN 'n' THEN 'SET NULL'
    WHEN 'd' THEN 'SET DEFAULT'
  END AS on_delete
FROM pg_constraint AS con
WHERE con.contype = 'f'
  AND con.confrelid = 'public.listings'::regclass
ORDER BY 1, 2;

-- Corrupt/nonstandard media paths: skip them, do NOT auto-delete.
SELECT
  l.id AS listing_id,
  COUNT(*) AS unexpected_paths
FROM public.listings AS l
CROSS JOIN LATERAL unnest(COALESCE(l.image_paths, ARRAY[]::text[])) AS p(path)
WHERE p.path IS NULL
   OR NOT starts_with(p.path, l.user_id::text || '/' || l.id::text || '/')
GROUP BY l.id
ORDER BY 2 DESC
LIMIT 100;
