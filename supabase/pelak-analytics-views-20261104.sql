-- PELAK analytics: approximate distinct browser-tab sessions per Tehran calendar day.
-- Install once in Supabase SQL Editor. No old listing rows or images are modified.
BEGIN;

CREATE TABLE IF NOT EXISTS public.pelak_analytics_visits (
  visitor_key uuid NOT NULL,
  visit_day date NOT NULL DEFAULT ((now() AT TIME ZONE 'Asia/Tehran')::date),
  listing_id uuid NULL REFERENCES public.listings(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS pelak_analytics_site_daily_uniq
  ON public.pelak_analytics_visits (visit_day, visitor_key)
  WHERE listing_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS pelak_analytics_listing_daily_uniq
  ON public.pelak_analytics_visits (visit_day, visitor_key, listing_id)
  WHERE listing_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS pelak_analytics_day_idx
  ON public.pelak_analytics_visits (visit_day);

CREATE INDEX IF NOT EXISTS pelak_analytics_listing_day_idx
  ON public.pelak_analytics_visits (listing_id, visit_day);

ALTER TABLE public.pelak_analytics_visits ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.pelak_analytics_visits FROM PUBLIC, anon, authenticated;

-- Public visitors can record a visit, but cannot read analytics records.
-- The server generates the day; client-supplied listing IDs are verified as published.
CREATE OR REPLACE FUNCTION public.pelak_analytics_record(
  p_visitor_key uuid,
  p_listing_id uuid DEFAULT NULL
)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $$
BEGIN
  IF p_visitor_key IS NULL THEN RETURN false; END IF;

  IF p_listing_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM public.listings l
    WHERE l.id = p_listing_id AND l.listing_status = 'published'
  ) THEN
    RETURN false;
  END IF;

  INSERT INTO public.pelak_analytics_visits
    (visitor_key, visit_day, listing_id)
  VALUES
    (p_visitor_key, (now() AT TIME ZONE 'Asia/Tehran')::date, p_listing_id)
  ON CONFLICT DO NOTHING;

  RETURN true;
END;
$$;

REVOKE ALL ON FUNCTION public.pelak_analytics_record(uuid, uuid)
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.pelak_analytics_record(uuid, uuid)
  TO anon, authenticated;

-- Private reporting: only the super_admin can read aggregate data.
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
  v_days integer := LEAST(GREATEST(COALESCE(p_days, 30), 1), 90);
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
    SELECT generate_series(v_start, v_today, INTERVAL '1 day')::date AS day
  ) days
  LEFT JOIN (
    SELECT
      v.visit_day,
      COUNT(*) FILTER (WHERE v.listing_id IS NULL) AS site_views,
      COUNT(*) FILTER (WHERE v.listing_id IS NOT NULL) AS listing_views
    FROM public.pelak_analytics_visits v
    WHERE v.visit_day BETWEEN v_start AND v_today
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
