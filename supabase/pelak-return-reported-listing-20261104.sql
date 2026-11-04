-- PELAK | Return a reported published/administratively archived listing
-- to its owner for correction, preserving its listing ID and private photos.
-- Install once in Supabase SQL Editor. Does NOT delete any data or schedule jobs.
BEGIN;

CREATE OR REPLACE FUNCTION public.pelak_owner_return_reported_listing(
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
  v_current_review text;
  v_deadline timestamptz;
BEGIN
  -- Only the site owner can initiate a return-for-revision decision.
  IF auth.uid() IS NULL OR NOT EXISTS (
    SELECT 1 FROM public.admin_roles a
    WHERE a.user_id = auth.uid()
      AND a.role = 'super_admin'
  ) THEN
    RAISE EXCEPTION 'Access denied';
  END IF;

  IF char_length(v_reason) NOT BETWEEN 5 AND 1500 THEN
    RAISE EXCEPTION 'Revision reason must be 5 to 1500 characters';
  END IF;

  -- Reports that have been dismissed cannot be reused as a moderation action.
  SELECT * INTO v_report
  FROM public.pelak_listing_reports
  WHERE id = p_report_id
  FOR UPDATE;

  IF NOT FOUND OR v_report.status NOT IN ('open', 'reviewed') THEN
    RAISE EXCEPTION 'Active report not found';
  END IF;

  SELECT * INTO v_listing
  FROM public.listings
  WHERE id = v_report.listing_id
  FOR UPDATE;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Listing not found';
  END IF;

  -- Only published items or items archived by staff can be returned.
  IF v_listing.listing_status NOT IN ('published', 'archived') THEN
    RAISE EXCEPTION 'Listing is not eligible for return for revision';
  END IF;

  -- Do not resurrect a seller-cancelled or expired listing, or purged media.
  IF v_listing.listing_status = 'archived' AND
     v_listing.archive_reason IN ('cancelled', 'expired') THEN
    RAISE EXCEPTION 'Cancelled or expired listings cannot be restored here';
  END IF;

  IF v_listing.media_purged_at IS NOT NULL THEN
    RAISE EXCEPTION 'Archived media was already purged; manual review needed';
  END IF;

  SELECT review_state INTO v_current_review
  FROM public.pelak_listing_rejections
  WHERE listing_id = v_listing.id
  FOR UPDATE;

  IF v_current_review = 'needs_revision' THEN
    RAISE EXCEPTION 'Listing is already awaiting revision';
  END IF;

  v_deadline := now() + INTERVAL '7 days';

  -- Becomes the SAME private draft with the SAME image paths.
  -- Reset all archival cleanup scheduling so the seller has seven full days.
  UPDATE public.listings
  SET listing_status = 'draft',
      archive_reason = NULL,
      archived_at = NULL,
      purge_after = NULL,
      published_at = NULL,
      expires_at = NULL,
      updated_at = now()
  WHERE id = v_listing.id;

  INSERT INTO public.pelak_listing_rejections(
    listing_id, owner_id, reason, review_state,
    rejected_at, delete_after, resubmitted_at, updated_at
  )
  VALUES (
    v_listing.id, v_listing.user_id, v_reason, 'needs_revision',
    now(), v_deadline, NULL, now()
  )
  ON CONFLICT (listing_id)
  DO UPDATE SET
    owner_id = EXCLUDED.owner_id,
    reason = EXCLUDED.reason,
    review_state = 'needs_revision',
    rejected_at = EXCLUDED.rejected_at,
    delete_after = EXCLUDED.delete_after,
    resubmitted_at = NULL,
    updated_at = now();

  -- The report remains in the owner-only audit inbox.
  UPDATE public.pelak_listing_reports
  SET status = 'reviewed',
      reviewed_by = auth.uid(),
      reviewed_at = now(),
      admin_note = 'Returned to seller for correction: ' || left(v_reason, 450)
  WHERE id = p_report_id;

  RETURN jsonb_build_object(
    'success', true,
    'listing_id', v_listing.id,
    'report_id', p_report_id,
    'review_state', 'needs_revision',
    'delete_after', v_deadline
  );
END;
$$;

REVOKE ALL ON FUNCTION
  public.pelak_owner_return_reported_listing(uuid, text)
  FROM PUBLIC, anon, authenticated;

GRANT EXECUTE ON FUNCTION
  public.pelak_owner_return_reported_listing(uuid, text)
  TO authenticated;

COMMIT;
