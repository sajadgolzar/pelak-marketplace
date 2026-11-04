-- PELAK: extend owner analytics range to 10 years (safe replacement; keeps existing visit records).
BEGIN;

CREATE OR REPLACE FUNCTION public.pelak_analytics_report(
  p_days integer DEFAULT 30,
  p_page integer DEFAULT 1,
  p_search text DEFAULT ''
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $$
DECLARE
  v_days integer := LEAST(GREATEST(COALESCE(p_days, 30), 1), 3650);
  v_page integer := LEAST(GREATEST(COALESCE(p_page, 1), 1), 100000);
  v_search text := LEFT(BTRIM(COALESCE(p_search, '')), 80);
  v_today date := (now() AT TIME ZONE 'Asia/Tehran')::date;
  v_start date;
  v_site_count bigint := 0;
  v_listing_count bigint := 0;
  v_listings_total bigint := 0;
  v_daily jsonb := '[]'::jsonb;
  v_listings jsonb := '[]'::jsonb;
BEGIN
  IF auth.uid() IS NULL OR NOT EXISTS (
    SELECT 1 FROM public.admin_roles ar
    WHERE ar.user_id = auth.uid() AND ar.role = 'super_admin'
  ) THEN
    RAISE EXCEPTION 'Access denied';
  END IF;

  v_start := v_today - (v_days - 1);

  SELECT
    COUNT(*) FILTER (WHERE v.listing_id IS NULL),
    COUNT(*) FILTER (WHERE v.listing_id IS NOT NULL)
  INTO v_site_count, v_listing_count
  FROM public.pelak_analytics_visits v
  WHERE v.visit_day BETWEEN v_start AND v_today;

  SELECT COALESCE(jsonb_agg(
    jsonb_build_object(
      'day', days.day,
      'site_views', COALESCE(aggregate.site_views, 0),
      'listing_views', COALESCE(aggregate.listing_views, 0)
    ) ORDER BY days.day
  ), '[]'::jsonb)
  INTO v_daily
  FROM (
    SELECT generate_series(GREATEST(v_start, v_today - 13), v_today, INTERVAL '1 day')::date AS day
  ) days
  LEFT JOIN (
    SELECT
      v.visit_day,
      COUNT(*) FILTER (WHERE v.listing_id IS NULL) AS site_views,
      COUNT(*) FILTER (WHERE v.listing_id IS NOT NULL) AS listing_views
    FROM public.pelak_analytics_visits v
    WHERE v.visit_day BETWEEN GREATEST(v_start, v_today - 13) AND v_today
    GROUP BY v.visit_day
  ) aggregate ON aggregate.visit_day = days.day;

  SELECT COUNT(*) INTO v_listings_total
  FROM public.listings l
  WHERE v_search = ''
    OR l.title ILIKE '%' || v_search || '%'
    OR l.make ILIKE '%' || v_search || '%'
    OR l.model ILIKE '%' || v_search || '%';

  SELECT COALESCE(
    jsonb_agg(jsonb_build_object(
      'listing_id', x.listing_id,
      'title', x.title,
      'listing_status', x.listing_status,
      'views', x.views
    ) ORDER BY x.views DESC, x.title), '[]'::jsonb
  )
  INTO v_listings
  FROM (
    SELECT
      l.id AS listing_id, l.title, l.listing_status,
      COUNT(v.visitor_key)::bigint AS views
    FROM public.listings l
    LEFT JOIN public.pelak_analytics_visits v
      ON v.listing_id = l.id
      AND v.visit_day BETWEEN v_start AND v_today
    WHERE v_search = ''
      OR l.title ILIKE '%' || v_search || '%'
      OR l.make ILIKE '%' || v_search || '%'
      OR l.model ILIKE '%' || v_search || '%'
    GROUP BY l.id, l.title, l.listing_status, l.created_at
    ORDER BY COUNT(v.visitor_key) DESC, l.created_at DESC
    LIMIT 20 OFFSET ((v_page - 1) * 20)
  ) x;

  RETURN jsonb_build_object(
    'days', v_days,
    'page', v_page,
    'page_size', 20,
    'site_views', v_site_count,
    'listing_views', v_listing_count,
    'listings_total', v_listings_total,
    'daily', v_daily,
    'listings', v_listings
  );
END;
$$;

REVOKE ALL ON FUNCTION public.pelak_analytics_report(integer, integer, text)
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.pelak_analytics_report(integer, integer, text)
  TO authenticated;



-- Public reports and owner review functions.
-- PELAK listing safety reports, 2026-11-04
-- Users must sign in; only owner can read/decide reports through RPC.
-- No automatic listing suppression/deletion.
CREATE TABLE IF NOT EXISTS public.pelak_listing_reports (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  listing_id uuid NOT NULL REFERENCES public.listings(id) ON DELETE CASCADE,
  reporter_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  category text NOT NULL CHECK (category IN (
    'fraud','incorrect','wrong_photos','duplicate','suspicious_contact','other'
  )),
  details text NOT NULL CHECK (char_length(btrim(details)) BETWEEN 10 AND 1200),
  status text NOT NULL DEFAULT 'open'
    CHECK (status IN ('open','reviewed','dismissed')),
  created_at timestamptz NOT NULL DEFAULT now(),
  reviewed_at timestamptz,
  reviewed_by uuid,
  admin_note text
);

CREATE UNIQUE INDEX IF NOT EXISTS pelak_listing_reports_open_once
  ON public.pelak_listing_reports(listing_id, reporter_id, category)
  WHERE status = 'open';

CREATE INDEX IF NOT EXISTS pelak_listing_reports_recent
  ON public.pelak_listing_reports(status, created_at DESC);

CREATE INDEX IF NOT EXISTS pelak_listing_reports_reporter_recent
  ON public.pelak_listing_reports(reporter_id, created_at DESC);

ALTER TABLE public.pelak_listing_reports ENABLE ROW LEVEL SECURITY;

-- The table itself is not publicly readable or writable.
-- All access is mediated by SECURITY DEFINER RPCs with explicit checks.
REVOKE ALL ON TABLE public.pelak_listing_reports
  FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.pelak_report_listing(
  p_listing_id uuid,
  p_category text,
  p_details text
)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $$
DECLARE
  v_uid uuid := auth.uid();
  v_owner uuid;
  v_details text := btrim(coalesce(p_details,''));
  v_id uuid;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'Sign in required';
  END IF;

  IF p_listing_id IS NULL OR p_category IS NULL
     OR p_category NOT IN (
       'fraud','incorrect','wrong_photos','duplicate','suspicious_contact','other'
     )
  THEN
    RAISE EXCEPTION 'Invalid listing or report category';
  END IF;

  IF char_length(v_details) NOT BETWEEN 10 AND 1200 THEN
    RAISE EXCEPTION 'Report explanation must be 10 to 1200 characters';
  END IF;

  SELECT l.user_id INTO v_owner
  FROM public.listings l
  WHERE l.id = p_listing_id AND l.listing_status = 'published';

  IF v_owner IS NULL THEN
    RAISE EXCEPTION 'Only published listings can be reported';
  END IF;

  IF v_owner = v_uid THEN
    RAISE EXCEPTION 'You cannot report your own listing';
  END IF;

  -- Account-level spam guard, across listings and categories.
  IF (
    SELECT count(*) FROM public.pelak_listing_reports r
    WHERE r.reporter_id = v_uid
      AND r.created_at >= now() - interval '24 hours'
  ) >= 5 THEN
    RAISE EXCEPTION 'Daily report limit reached';
  END IF;

  IF EXISTS (
    SELECT 1 FROM public.pelak_listing_reports r
    WHERE r.reporter_id = v_uid
      AND r.listing_id = p_listing_id
      AND r.category = p_category
      AND r.status = 'open'
  ) THEN
    RAISE EXCEPTION 'You already sent an open report for this reason';
  END IF;

  INSERT INTO public.pelak_listing_reports(
    listing_id, reporter_id, category, details
  ) VALUES (p_listing_id, v_uid, p_category, v_details)
  RETURNING id INTO v_id;

  RETURN v_id;
END;
$$;

REVOKE ALL ON FUNCTION public.pelak_report_listing(uuid,text,text)
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.pelak_report_listing(uuid,text,text)
  TO authenticated;

CREATE OR REPLACE FUNCTION public.pelak_owner_listing_reports(
  p_only_open boolean DEFAULT true
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $$
DECLARE
  v_rows jsonb;
  v_open_count bigint;
BEGIN
  IF auth.uid() IS NULL OR NOT EXISTS (
    SELECT 1 FROM public.admin_roles a
    WHERE a.user_id = auth.uid() AND a.role='super_admin'
  ) THEN
    RAISE EXCEPTION 'Access denied';
  END IF;

  SELECT count(*) INTO v_open_count
  FROM public.pelak_listing_reports
  WHERE status='open';

  SELECT coalesce(jsonb_agg(
    jsonb_build_object(
      'id', x.id,
      'listing_id', x.listing_id,
      'title', x.title,
      'listing_status', x.listing_status,
      'category', x.category,
      'details', x.details,
      'status', x.status,
      'created_at', x.created_at,
      'reviewed_at', x.reviewed_at,
      'admin_note', x.admin_note
    ) ORDER BY x.created_at DESC
  ), '[]'::jsonb)
  INTO v_rows
  FROM (
    SELECT r.id,r.listing_id,l.title,l.listing_status,
           r.category,r.details,r.status,
           r.created_at,r.reviewed_at,r.admin_note
    FROM public.pelak_listing_reports r
    JOIN public.listings l ON l.id=r.listing_id
    WHERE (coalesce(p_only_open,true)=false OR r.status='open')
    ORDER BY r.created_at DESC
    LIMIT 100
  ) x;

  RETURN jsonb_build_object('open_count', v_open_count, 'reports', v_rows);
END;
$$;

REVOKE ALL ON FUNCTION public.pelak_owner_listing_reports(boolean)
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.pelak_owner_listing_reports(boolean)
  TO authenticated;

CREATE OR REPLACE FUNCTION public.pelak_owner_review_listing_report(
  p_report_id uuid,
  p_decision text,
  p_note text DEFAULT NULL
)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $$
DECLARE
  v_count integer;
BEGIN
  IF auth.uid() IS NULL OR NOT EXISTS (
    SELECT 1 FROM public.admin_roles a
    WHERE a.user_id=auth.uid() AND a.role='super_admin'
  ) THEN
    RAISE EXCEPTION 'Access denied';
  END IF;

  IF p_decision NOT IN ('reviewed','dismissed') OR p_decision IS NULL THEN
    RAISE EXCEPTION 'Invalid review decision';
  END IF;

  IF char_length(coalesce(p_note,'')) > 500 THEN
    RAISE EXCEPTION 'Review note is too long';
  END IF;

  UPDATE public.pelak_listing_reports
  SET status=p_decision,
      reviewed_by=auth.uid(),
      reviewed_at=now(),
      admin_note=nullif(btrim(coalesce(p_note,'')),'')
  WHERE id=p_report_id AND status='open';

  GET DIAGNOSTICS v_count=ROW_COUNT;
  RETURN v_count=1;
END;
$$;

REVOKE ALL ON FUNCTION public.pelak_owner_review_listing_report(uuid,text,text)
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.pelak_owner_review_listing_report(uuid,text,text)
  TO authenticated;

COMMIT;
