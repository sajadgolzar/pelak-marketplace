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

COMMIT;
