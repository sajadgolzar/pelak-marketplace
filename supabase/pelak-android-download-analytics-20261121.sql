CREATE TABLE public.pelak_android_download_clicks (
  click_day date NOT NULL DEFAULT ((now() AT TIME ZONE 'Asia/Tehran')::date),
  visitor_key uuid NOT NULL,
  PRIMARY KEY (click_day, visitor_key)
);
ALTER TABLE public.pelak_android_download_clicks ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.pelak_android_download_clicks FROM PUBLIC, anon, authenticated;

CREATE FUNCTION public.pelak_record_android_download_click(p_visitor_key uuid)
RETURNS void LANGUAGE sql SECURITY DEFINER SET search_path = '' AS $$
  INSERT INTO public.pelak_android_download_clicks (visitor_key)
  SELECT p_visitor_key WHERE p_visitor_key IS NOT NULL
  ON CONFLICT DO NOTHING;
$$;
REVOKE ALL ON FUNCTION public.pelak_record_android_download_click(uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.pelak_record_android_download_click(uuid) TO anon, authenticated;

CREATE FUNCTION public.pelak_android_download_click_count(p_days integer)
RETURNS bigint LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
  today date := (now() AT TIME ZONE 'Asia/Tehran')::date;
  days integer := LEAST(GREATEST(COALESCE(p_days, 30), 1), 3650);
BEGIN
  IF auth.uid() IS NULL OR NOT EXISTS (
    SELECT 1 FROM public.admin_roles WHERE user_id = auth.uid() AND role = 'super_admin'
  ) THEN
    RAISE EXCEPTION 'Access denied' USING ERRCODE = '42501';
  END IF;
  RETURN (SELECT count(*) FROM public.pelak_android_download_clicks
          WHERE click_day BETWEEN today - (days - 1) AND today);
END;
$$;
REVOKE ALL ON FUNCTION public.pelak_android_download_click_count(integer) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.pelak_android_download_click_count(integer) TO authenticated;
