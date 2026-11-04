-- PELAK moderation: permanently reject a reported listing without deleting evidence.
-- The existing prepare/finalize owner-only functions handle actual permanent deletion.
-- This migration does not delete or archive any existing listing by itself.
BEGIN;

CREATE OR REPLACE FUNCTION public.pelak_owner_reject_reported_listing(
  p_report_id uuid,
  p_reason text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $$
DECLARE
  v_report public.pelak_listing_reports%ROWTYPE;
  v_listing public.listings%ROWTYPE;
  v_reason text := btrim(coalesce(p_reason, ''));
BEGIN
  IF auth.uid() IS NULL OR NOT EXISTS (
    SELECT 1 FROM public.admin_roles a
    WHERE a.user_id = auth.uid() AND a.role = 'super_admin'
  ) THEN
    RAISE EXCEPTION 'Access denied';
  END IF;

  IF char_length(v_reason) NOT BETWEEN 5 AND 1200 THEN
    RAISE EXCEPTION 'Reason must be between 5 and 1200 characters';
  END IF;

  SELECT * INTO v_report
  FROM public.pelak_listing_reports
  WHERE id = p_report_id
  FOR UPDATE;

  IF NOT FOUND OR v_report.status = 'dismissed' THEN
    RAISE EXCEPTION 'Report is unavailable or dismissed';
  END IF;

  SELECT * INTO v_listing
  FROM public.listings
  WHERE id = v_report.listing_id
  FOR UPDATE;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Listing no longer exists';
  END IF;

  IF v_listing.listing_status NOT IN ('published', 'draft', 'archived') THEN
    RAISE EXCEPTION 'Listing cannot be permanently rejected in its current status';
  END IF;

  IF v_listing.listing_status = 'archived' AND
     (v_listing.archive_reason IN ('expired','cancelled')
      OR v_listing.media_purged_at IS NOT NULL) THEN
    RAISE EXCEPTION 'Expired/cancelled or purged listing cannot be moderated here';
  END IF;

  IF coalesce(v_listing.archive_reason, '') LIKE 'moderation_rejected:%' THEN
    RAISE EXCEPTION 'Listing has already been permanently rejected';
  END IF;

  UPDATE public.listings
  SET listing_status = 'archived',
      archive_reason = 'moderation_rejected:' || v_reason,
      archived_at = now(),
      purge_after = NULL,
      expires_at = NULL,
      updated_at = now()
  WHERE id = v_listing.id;

  -- A previous 7-day edit request is cancelled by permanent rejection.
  -- This row remains private to staff and the seller.
  DELETE FROM public.pelak_listing_rejections
  WHERE listing_id = v_listing.id;

  UPDATE public.pelak_listing_reports
  SET status = 'reviewed',
      reviewed_by = auth.uid(),
      reviewed_at = now(),
      admin_note = 'Permanently rejected: ' || left(v_reason, 450)
  WHERE id = p_report_id;

  RETURN jsonb_build_object(
    'success', true,
    'listing_id', v_listing.id,
    'listing_status', 'archived'
  );
END;
$$;

REVOKE ALL ON FUNCTION public.pelak_owner_reject_reported_listing(uuid,text)
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.pelak_owner_reject_reported_listing(uuid,text)
  TO authenticated;

-- Prevent seller-facing relist RPCs from resurrecting a moderation ban.
-- Only a verified super_admin can explicitly change a banned listing status.
CREATE OR REPLACE FUNCTION public.pelak_guard_moderation_rejected_relist()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $$
BEGIN
  IF old.listing_status = 'archived'
     AND coalesce(old.archive_reason, '') LIKE 'moderation_rejected:%'
     AND new.listing_status IS DISTINCT FROM 'archived'
     AND NOT EXISTS (
       SELECT 1 FROM public.admin_roles a
       WHERE a.user_id = auth.uid() AND a.role = 'super_admin'
     )
  THEN
    RAISE EXCEPTION 'This listing was removed for a policy violation; contact support';
  END IF;
  RETURN new;
END;
$$;

DROP TRIGGER IF EXISTS pelak_guard_moderation_rejected_relist
  ON public.listings;
CREATE TRIGGER pelak_guard_moderation_rejected_relist
BEFORE UPDATE OF listing_status ON public.listings
FOR EACH ROW
EXECUTE FUNCTION public.pelak_guard_moderation_rejected_relist();

COMMIT;
