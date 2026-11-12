-- PELAK / Expired revision cleanup (prepared, NOT scheduled)
-- Install only after reviewing the read-only preflight query.
-- This migration itself does not delete an ad or a photo.
-- The service-role-only worker deletes only expired, unresubmitted revisions.
BEGIN;

CREATE TABLE IF NOT EXISTS public.pelak_revision_cleanup_jobs (
  listing_id uuid PRIMARY KEY,
  claim_token uuid,
  claimed_at timestamptz,
  attempt_count integer NOT NULL DEFAULT 0,
  completed_at timestamptz,
  last_error text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS pelak_revision_cleanup_active_idx
  ON public.pelak_revision_cleanup_jobs(claimed_at)
  WHERE completed_at IS NULL;
CREATE INDEX IF NOT EXISTS pelak_revision_cleanup_due_idx
  ON public.pelak_listing_rejections(delete_after, listing_id)
  WHERE review_state = 'needs_revision';

ALTER TABLE public.pelak_revision_cleanup_jobs ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.pelak_revision_cleanup_jobs FROM PUBLIC, anon, authenticated;
GRANT SELECT, INSERT, UPDATE ON public.pelak_revision_cleanup_jobs TO service_role;

-- Claim under row locks: a seller resubmission that finished first wins.
-- The first claim archives the expired draft. That prevents normal seller edits
-- while the service deletes its own, uniquely named Storage folder.
CREATE OR REPLACE FUNCTION public.pelak_revision_cleanup_claim(
  p_limit integer DEFAULT 5
)
RETURNS TABLE(listing_id uuid, owner_id uuid, claim_token uuid)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $$
DECLARE
  v_row record;
  v_prefix text;
  v_token uuid;
BEGIN
  IF auth.role() IS DISTINCT FROM 'service_role' THEN
    RAISE EXCEPTION 'Service role only';
  END IF;

  FOR v_row IN
    SELECT r.listing_id, l.user_id, l.image_paths, l.listing_status
    FROM public.pelak_listing_rejections AS r
    INNER JOIN public.listings AS l ON l.id = r.listing_id
    LEFT JOIN public.pelak_revision_cleanup_jobs AS j
      ON j.listing_id = r.listing_id
    WHERE r.review_state = 'needs_revision'
      AND r.delete_after IS NOT NULL
      AND r.delete_after <= now()
      AND (
        (l.listing_status = 'draft' AND l.archive_reason IS NULL)
        OR (l.listing_status = 'archived'
            AND l.archive_reason = 'revision_expired_cleanup')
      )
      AND (
        j.listing_id IS NULL
        OR (j.completed_at IS NULL
            AND (j.claimed_at IS NULL
                 OR j.claimed_at < now() - interval '20 minutes'))
      )
    ORDER BY r.delete_after, r.listing_id
    LIMIT LEAST(GREATEST(COALESCE(p_limit, 5), 1), 10)
    FOR UPDATE OF r, l SKIP LOCKED
  LOOP
    v_prefix := v_row.user_id::text || '/' || v_row.listing_id::text || '/';

    -- Stop rather than delete any legacy/non-owned path.
    IF EXISTS (
      SELECT 1 FROM unnest(COALESCE(v_row.image_paths, ARRAY[]::text[])) AS p(path)
      WHERE p.path IS NULL OR NOT starts_with(p.path, v_prefix)
    ) THEN
      INSERT INTO public.pelak_revision_cleanup_jobs
        (listing_id, claimed_at, last_error)
      VALUES (v_row.listing_id, now(), 'Manual review: image path outside listing folder')
      ON CONFLICT ON CONSTRAINT pelak_revision_cleanup_jobs_pkey DO UPDATE
        SET claimed_at = now(),
            last_error = EXCLUDED.last_error,
            updated_at = now();
      CONTINUE;
    END IF;

    -- Do not delete a folder still referenced by another listing.
    IF EXISTS (
      SELECT 1
      FROM public.listings AS other
      CROSS JOIN LATERAL
        unnest(COALESCE(other.image_paths, ARRAY[]::text[])) AS p(path)
      WHERE other.id <> v_row.listing_id
        AND starts_with(p.path, v_prefix)
    ) THEN
      INSERT INTO public.pelak_revision_cleanup_jobs
        (listing_id, claimed_at, last_error)
      VALUES (v_row.listing_id, now(), 'Manual review: folder referenced by another listing')
      ON CONFLICT ON CONSTRAINT pelak_revision_cleanup_jobs_pkey DO UPDATE
        SET claimed_at = now(),
            last_error = EXCLUDED.last_error,
            updated_at = now();
      CONTINUE;
    END IF;

    IF v_row.listing_status = 'draft' THEN
      UPDATE public.listings AS l
      SET listing_status = 'archived',
          archive_reason = 'revision_expired_cleanup',
          archived_at = now(),
          purge_after = NULL,
          updated_at = now()
      WHERE l.id = v_row.listing_id;
    END IF;

    v_token := gen_random_uuid();
    INSERT INTO public.pelak_revision_cleanup_jobs AS j
      (listing_id, claim_token, claimed_at, attempt_count, last_error)
    VALUES (v_row.listing_id, v_token, now(), 1, NULL)
    ON CONFLICT ON CONSTRAINT pelak_revision_cleanup_jobs_pkey DO UPDATE
      SET claim_token = EXCLUDED.claim_token,
          claimed_at = EXCLUDED.claimed_at,
          attempt_count = j.attempt_count + 1,
          last_error = NULL,
          updated_at = now();

    listing_id := v_row.listing_id;
    owner_id := v_row.user_id;
    claim_token := v_token;
    RETURN NEXT;
  END LOOP;
END;
$$;

-- Finalize only once the Storage API has removed EVERY object in this
-- listing-specific folder. Never DELETE storage.objects using SQL.
CREATE OR REPLACE FUNCTION public.pelak_revision_cleanup_finish(
  p_listing_id uuid,
  p_claim_token uuid
)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $$
DECLARE
  v_review record;
  v_listing public.listings%ROWTYPE;
  v_job public.pelak_revision_cleanup_jobs%ROWTYPE;
  v_prefix text;
  v_deleted integer;
BEGIN
  IF auth.role() IS DISTINCT FROM 'service_role' THEN
    RAISE EXCEPTION 'Service role only';
  END IF;

  SELECT r.review_state, r.delete_after INTO v_review
  FROM public.pelak_listing_rejections AS r
  WHERE r.listing_id = p_listing_id
  FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Revision no longer exists';
  END IF;

  SELECT l.* INTO v_listing
  FROM public.listings AS l
  WHERE l.id = p_listing_id
  FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Listing no longer exists';
  END IF;

  SELECT j.* INTO v_job
  FROM public.pelak_revision_cleanup_jobs AS j
  WHERE j.listing_id = p_listing_id
  FOR UPDATE;

  IF NOT FOUND OR v_job.completed_at IS NOT NULL
     OR v_job.claim_token IS DISTINCT FROM p_claim_token THEN
    RAISE EXCEPTION 'Cleanup claim no longer valid';
  END IF;

  IF v_review.review_state <> 'needs_revision'
     OR v_review.delete_after IS NULL
     OR v_review.delete_after > now()
     OR v_listing.listing_status <> 'archived'
     OR v_listing.archive_reason IS DISTINCT FROM 'revision_expired_cleanup' THEN
    RAISE EXCEPTION 'Listing not eligible for automatic deletion';
  END IF;

  v_prefix := v_listing.user_id::text || '/' || p_listing_id::text || '/';

  IF EXISTS (
    SELECT 1 FROM unnest(COALESCE(v_listing.image_paths, ARRAY[]::text[])) AS p(path)
    WHERE p.path IS NULL OR NOT starts_with(p.path, v_prefix)
  ) THEN
    RAISE EXCEPTION 'Unsafe listing image paths';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM public.listings AS other
    CROSS JOIN LATERAL
      unnest(COALESCE(other.image_paths, ARRAY[]::text[])) AS p(path)
    WHERE other.id <> p_listing_id
      AND starts_with(p.path, v_prefix)
  ) THEN
    RAISE EXCEPTION 'Another listing references these photos';
  END IF;

  IF EXISTS (
    SELECT 1 FROM storage.objects AS o
    WHERE o.bucket_id = 'car-images' AND starts_with(o.name, v_prefix)
  ) THEN
    RAISE EXCEPTION 'Storage folder is not empty';
  END IF;

  -- If any child table has a restrictive FK, the transaction aborts safely.
  -- No cascade rules are changed here.
  DELETE FROM public.pelak_listing_rejections AS r
  WHERE r.listing_id = p_listing_id;
  DELETE FROM public.listings AS l
  WHERE l.id = p_listing_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  IF v_deleted <> 1 THEN
    RAISE EXCEPTION 'Unexpected listing delete count';
  END IF;

  UPDATE public.pelak_revision_cleanup_jobs AS j
  SET completed_at = now(), claimed_at = NULL,
      last_error = NULL, updated_at = now()
  WHERE j.listing_id = p_listing_id;

  RETURN true;
END;
$$;

CREATE OR REPLACE FUNCTION public.pelak_revision_cleanup_fail(
  p_listing_id uuid,
  p_claim_token uuid,
  p_error text
)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $$
DECLARE
  v_count integer;
BEGIN
  IF auth.role() IS DISTINCT FROM 'service_role' THEN
    RAISE EXCEPTION 'Service role only';
  END IF;

  UPDATE public.pelak_revision_cleanup_jobs AS j
  SET last_error = LEFT(COALESCE(p_error, 'Unknown cleanup error'), 300),
      claimed_at = now(),
      updated_at = now()
  WHERE j.listing_id = p_listing_id
    AND j.claim_token = p_claim_token
    AND j.completed_at IS NULL;
  GET DIAGNOSTICS v_count = ROW_COUNT;
  RETURN v_count = 1;
END;
$$;

REVOKE ALL ON FUNCTION public.pelak_revision_cleanup_claim(integer)
  FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.pelak_revision_cleanup_finish(uuid,uuid)
  FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.pelak_revision_cleanup_fail(uuid,uuid,text)
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.pelak_revision_cleanup_claim(integer)
  TO service_role;
GRANT EXECUTE ON FUNCTION public.pelak_revision_cleanup_finish(uuid,uuid)
  TO service_role;
GRANT EXECUTE ON FUNCTION public.pelak_revision_cleanup_fail(uuid,uuid,text)
  TO service_role;

COMMIT;
