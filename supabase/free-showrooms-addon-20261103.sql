-- PELAK / free virtual showroom add-on
-- Review before running in Supabase SQL Editor. This file is NOT executed by GitHub.
-- Depends on existing public.pelak_dealer_profiles, listings, subscriptions and admin_roles.
-- Does NOT grant paid PRO/VIP access or change any existing payment verification routines.
-- Existing profiles and subscription rows are retained.

BEGIN;

ALTER TABLE public.pelak_dealer_profiles
  ADD COLUMN IF NOT EXISTS free_profile_public boolean NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS free_avatar_path text,
  ADD COLUMN IF NOT EXISTS free_cover_path text;

-- Public images are intentionally public; uploads are restricted to the authenticated
-- owner's UUID folder. Only converted JPEGs are accepted, max 5 MiB.
INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES ('pelak-showrooms', 'pelak-showrooms', true, 5242880, ARRAY['image/jpeg']::text[])
ON CONFLICT (id) DO NOTHING;

DO $policies$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'storage' AND tablename = 'objects'
      AND policyname = 'pelak_showrooms_owner_upload_v1'
  ) THEN
    CREATE POLICY pelak_showrooms_owner_upload_v1
      ON storage.objects
      FOR INSERT TO authenticated
      WITH CHECK (
        bucket_id = 'pelak-showrooms'
        AND (storage.foldername(name))[1] = (SELECT auth.uid())::text
        AND (
          storage.filename(name) LIKE 'avatar-%.jpg'
          OR storage.filename(name) LIKE 'cover-%.jpg'
        )
      );
  END IF;
END;
$policies$;

-- Read the signed-in user's own record through a narrowly-scoped RPC, since
-- direct table SELECT is intentionally blocked by the existing RLS design.
CREATE OR REPLACE FUNCTION public.pelak_free_showroom_me()
RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER SET search_path TO ''
AS $function$
  SELECT to_jsonb(p)
  FROM public.pelak_dealer_profiles p
  WHERE p.user_id = (SELECT auth.uid())
  LIMIT 1;
$function$;

REVOKE ALL ON FUNCTION public.pelak_free_showroom_me() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.pelak_free_showroom_me() TO authenticated;

-- A user may attach only images they already uploaded in their own UUID folder.
-- The front-end saves the text-only free profile first, then uploads images.
CREATE OR REPLACE FUNCTION public.pelak_free_showroom_media_save(
  p_avatar_path text DEFAULT NULL,
  p_cover_path text DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_result public.pelak_dealer_profiles%ROWTYPE;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'Login required';
  END IF;

  IF p_avatar_path IS NOT NULL AND (
    NOT starts_with(p_avatar_path, v_uid::text || '/avatar-')
    OR right(p_avatar_path, 4) <> '.jpg'
    OR NOT EXISTS (
      SELECT 1 FROM storage.objects o
      WHERE o.bucket_id = 'pelak-showrooms'
        AND o.name = p_avatar_path
    )
  ) THEN
    RAISE EXCEPTION 'Invalid profile image';
  END IF;

  IF p_cover_path IS NOT NULL AND (
    NOT starts_with(p_cover_path, v_uid::text || '/cover-')
    OR right(p_cover_path, 4) <> '.jpg'
    OR NOT EXISTS (
      SELECT 1 FROM storage.objects o
      WHERE o.bucket_id = 'pelak-showrooms'
        AND o.name = p_cover_path
    )
  ) THEN
    RAISE EXCEPTION 'Invalid cover image';
  END IF;

  UPDATE public.pelak_dealer_profiles
  SET free_avatar_path = COALESCE(p_avatar_path, free_avatar_path),
      free_cover_path = COALESCE(p_cover_path, free_cover_path),
      updated_at = now()
  WHERE user_id = v_uid
    AND free_profile_public = true
    AND status <> 'rejected'
  RETURNING * INTO v_result;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Complete your free showroom profile first';
  END IF;

  RETURN jsonb_build_object(
    'free_avatar_path', v_result.free_avatar_path,
    'free_cover_path', v_result.free_cover_path
  );
END;
$function$;

REVOKE ALL ON FUNCTION public.pelak_free_showroom_media_save(text,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.pelak_free_showroom_media_save(text,text) TO authenticated;

-- The slug route intentionally exposes only PUBLIC profile fields and PUBLIC
-- listings. Never expose internal IDs, payment details or private image paths.
CREATE OR REPLACE FUNCTION public.pelak_free_showroom_public(p_slug text)
RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER SET search_path TO ''
AS $function$
  SELECT jsonb_build_object(
    'slug', p.slug,
    'name', p.business_name,
    'city', p.city,
    'bio', p.bio,
    'phone', p.public_phone,
    'address', p.address,
    'map', p.map_url,
    'logo', p.logo_url,
    'avatar_path', p.free_avatar_path,
    'cover_path', p.free_cover_path,
    'verified', p.status = 'approved',
    'plan', COALESCE(s.plan, 'free'),
    'listings', COALESCE((
      SELECT jsonb_agg(
        jsonb_build_object(
          'id', l.id,
          'title', l.title,
          'make', l.make,
          'model', l.model,
          'year', l.year,
          'price', l.price,
          'currency', l.currency,
          'price_negotiable', l.price_negotiable
        )
        ORDER BY l.created_at DESC
      )
      FROM (
        SELECT id, title, make, model, year, price, currency,
               price_negotiable, created_at
        FROM public.listings
        WHERE user_id = p.user_id AND listing_status = 'published'
        ORDER BY created_at DESC
        LIMIT 60
      ) l
    ), '[]'::jsonb)
  )
  FROM public.pelak_dealer_profiles p
  LEFT JOIN LATERAL (
    SELECT sub.plan
    FROM public.pelak_dealer_subscriptions sub
    JOIN public.admin_roles a
      ON a.user_id = sub.payment_verified_by
     AND a.role = 'super_admin'
    WHERE sub.dealer_id = p.user_id
      AND p.status = 'approved'
      AND sub.status = 'active'
      AND sub.plan IN ('pro','vip')
      AND sub.paid_amount > 0
      AND sub.starts_at <= now()
      AND sub.ends_at > now()
    ORDER BY CASE WHEN sub.plan = 'vip' THEN 0 ELSE 1 END,
             sub.ends_at DESC
    LIMIT 1
  ) s ON true
  WHERE p.slug = p_slug
    AND p.status IN ('pending', 'approved')
    AND (
      p.free_profile_public = true
      OR s.plan IS NOT NULL
    )
  LIMIT 1;
$function$;

REVOKE ALL ON FUNCTION public.pelak_free_showroom_public(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.pelak_free_showroom_public(text) TO anon, authenticated;

-- Extend the already-installed free directory with optional image paths;
-- preserve verified paid-only legacy dealers and the confirmed paid-tier rules.
CREATE OR REPLACE FUNCTION public.pelak_public_showroom_directory()
RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER SET search_path TO ''
AS $function$
  SELECT COALESCE(
    jsonb_agg(
      jsonb_build_object(
        'slug', p.slug,
        'name', p.business_name,
        'city', p.city,
        'bio', left(coalesce(p.bio,''),140),
        'logo', p.logo_url,
        'avatar_path', p.free_avatar_path,
        'cover_path', p.free_cover_path,
        'verified', p.status = 'approved',
        'plan', COALESCE(s.plan,'free'),
        'cars', (
          SELECT count(*)
          FROM public.listings l
          WHERE l.user_id = p.user_id
            AND l.listing_status = 'published'
        )
      ) ORDER BY p.business_name
    ),
    '[]'::jsonb
  )
  FROM public.pelak_dealer_profiles p
  LEFT JOIN LATERAL (
    SELECT sub.plan
    FROM public.pelak_dealer_subscriptions sub
    JOIN public.admin_roles a
      ON a.user_id = sub.payment_verified_by
     AND a.role = 'super_admin'
    WHERE sub.dealer_id = p.user_id
      AND p.status = 'approved'
      AND sub.status = 'active'
      AND sub.plan IN ('pro','vip')
      AND sub.paid_amount > 0
      AND sub.starts_at <= now()
      AND sub.ends_at > now()
    ORDER BY CASE WHEN sub.plan = 'vip' THEN 0 ELSE 1 END,
             sub.ends_at DESC
    LIMIT 1
  ) s ON true
  WHERE nullif(btrim(p.slug),'') IS NOT NULL
    AND p.status IN ('pending','approved')
    AND (p.free_profile_public = true OR s.plan IS NOT NULL);
$function$;

REVOKE ALL ON FUNCTION public.pelak_public_showroom_directory() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.pelak_public_showroom_directory() TO anon, authenticated;

COMMIT;

-- Post-install checks (read-only):
-- SELECT to_regclass('public.pelak_dealer_profiles');
-- SELECT id, public, file_size_limit FROM storage.buckets WHERE id='pelak-showrooms';
-- SELECT proname FROM pg_proc WHERE proname LIKE 'pelak_free_showroom_%';
