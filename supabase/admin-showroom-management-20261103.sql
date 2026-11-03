-- PELAK | Owner-only showroom administration
-- Manual Supabase SQL Editor migration, NOT run automatically by merging GitHub.
-- Review, take a backup, and test with the super_admin account before enabling the UI.
-- This change DOES NOT alter original RLS, free membership, or PRO/VIP payment access.
-- Never cascades into listings, auth users, payments or other child records.

BEGIN;

-- 1. Complete owner-only showroom details (no unrestricted table SELECT).
CREATE OR REPLACE FUNCTION public.pelak_admin_showroom_detail(p_user_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
STABLE SECURITY DEFINER
SET search_path TO ''
AS $fn$
DECLARE
  v_result jsonb;
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM public.admin_roles a
    WHERE a.user_id = (SELECT auth.uid()) AND a.role = 'super_admin'
  ) THEN
    RAISE EXCEPTION 'Owner access required' USING ERRCODE='42501';
  END IF;

  SELECT jsonb_build_object(
    'user_id', p.user_id,
    'business_name', p.business_name,
    'city', p.city,
    'contact_phone', p.contact_phone,
    'public_phone', p.public_phone,
    'bio', p.bio,
    'address', p.address,
    'map_url', p.map_url,
    'logo_url', p.logo_url,
    'free_avatar_path', p.free_avatar_path,
    'free_cover_path', p.free_cover_path,
    'status', p.status,
    'free_profile_public', p.free_profile_public,
    'slug', p.slug,
    'owner_email', u.email,
    'created_at', p.created_at,
    'updated_at', p.updated_at,
    'reviewed_at', p.reviewed_at,
    'verified_paid_access', public.pelak_pro_access(p.user_id),
    'total_listings', (
      SELECT count(*) FROM public.listings l WHERE l.user_id = p.user_id
    ),
    'published_listings', (
      SELECT count(*) FROM public.listings l
      WHERE l.user_id = p.user_id AND l.listing_status = 'published'
    )
  ) INTO v_result
  FROM public.pelak_dealer_profiles p
  LEFT JOIN auth.users u ON u.id = p.user_id
  WHERE p.user_id = p_user_id;

  IF v_result IS NULL THEN
    RAISE EXCEPTION 'Showroom not found' USING ERRCODE='P0002';
  END IF;
  RETURN v_result;
END;
$fn$;

REVOKE ALL ON FUNCTION public.pelak_admin_showroom_detail(uuid)
FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.pelak_admin_showroom_detail(uuid)
TO authenticated;


-- 2. Explicitly chosen management actions. Free profile visibility and
-- identity verification are separate; paid subscriptions are never changed.
CREATE OR REPLACE FUNCTION public.pelak_admin_showroom_manage(
  p_user_id uuid,
  p_action text,
  p_new_status text DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $fn$
DECLARE
  v_row public.pelak_dealer_profiles%ROWTYPE;
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM public.admin_roles a
    WHERE a.user_id = (SELECT auth.uid()) AND a.role = 'super_admin'
  ) THEN
    RAISE EXCEPTION 'Owner access required' USING ERRCODE='42501';
  END IF;

  SELECT * INTO v_row
  FROM public.pelak_dealer_profiles
  WHERE user_id = p_user_id
  FOR UPDATE;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Showroom not found' USING ERRCODE='P0002';
  END IF;

  IF p_action = 'enable_public' THEN
    IF v_row.status = 'rejected' THEN
      RAISE EXCEPTION 'A rejected showroom cannot be made public';
    END IF;
    IF nullif(btrim(coalesce(v_row.slug, '')), '') IS NULL THEN
      RAISE EXCEPTION 'This showroom needs a slug before becoming public';
    END IF;
    UPDATE public.pelak_dealer_profiles
    SET free_profile_public = true, updated_at = now()
    WHERE user_id = p_user_id;
  ELSIF p_action = 'disable_public' THEN
    UPDATE public.pelak_dealer_profiles
    SET free_profile_public = false, updated_at = now()
    WHERE user_id = p_user_id;
  ELSIF p_action = 'set_status' THEN
    IF p_new_status IS NULL OR p_new_status NOT IN ('approved','pending','rejected') THEN
      RAISE EXCEPTION 'Invalid approval status';
    END IF;
    UPDATE public.pelak_dealer_profiles
    SET status = p_new_status,
        free_profile_public = CASE
          WHEN p_new_status = 'rejected' THEN false
          ELSE free_profile_public
        END,
        reviewed_at = CASE
          WHEN p_new_status = 'pending' THEN NULL
          ELSE now()
        END,
        reviewed_by = CASE
          WHEN p_new_status = 'pending' THEN NULL
          ELSE (SELECT auth.uid())
        END,
        updated_at = now()
    WHERE user_id = p_user_id;
  ELSE
    RAISE EXCEPTION 'Invalid management action';
  END IF;

  SELECT * INTO v_row
  FROM public.pelak_dealer_profiles WHERE user_id = p_user_id;

  RETURN jsonb_build_object(
    'user_id', v_row.user_id,
    'status', v_row.status,
    'free_profile_public', v_row.free_profile_public
  );
END;
$fn$;

REVOKE ALL ON FUNCTION public.pelak_admin_showroom_manage(uuid,text,text)
FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.pelak_admin_showroom_manage(uuid,text,text)
TO authenticated;


-- 3. Permanent deletion, ONLY if safe. Never cascade or silently erase
-- child showroom records, user accounts, listings, or profile media.
-- DB foreign keys and additional table discovery are both safety guards.
CREATE OR REPLACE FUNCTION public.pelak_admin_showroom_delete(
  p_user_id uuid,
  p_confirmation text
)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $fn$
DECLARE
  v_row public.pelak_dealer_profiles%ROWTYPE;
  v_fk record;
  v_column text;
  v_table record;
  v_has_links boolean;
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM public.admin_roles a
    WHERE a.user_id = (SELECT auth.uid()) AND a.role = 'super_admin'
  ) THEN
    RAISE EXCEPTION 'Owner access required' USING ERRCODE='42501';
  END IF;

  IF p_user_id IS NULL OR p_confirmation IS DISTINCT FROM 'DELETE:' || p_user_id::text THEN
    RAISE EXCEPTION 'Permanent deletion confirmation required'
      USING ERRCODE='42501';
  END IF;

  SELECT * INTO v_row
  FROM public.pelak_dealer_profiles
  WHERE user_id = p_user_id FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Showroom not found' USING ERRCODE='P0002';
  END IF;

  -- Preserve even unlinked image objects; do not orphan public uploaded photos.
  IF v_row.free_avatar_path IS NOT NULL OR v_row.free_cover_path IS NOT NULL THEN
    RAISE EXCEPTION 'Profile has uploaded photos; remove media safely before permanent deletion';
  END IF;

  -- Listings belong to the user, not to this showroom deletion.
  IF EXISTS (SELECT 1 FROM public.listings l WHERE l.user_id = p_user_id) THEN
    RAISE EXCEPTION 'Showroom owner has listings: permanent deletion blocked to protect ads';
  END IF;

  -- Refuse if any declared FK would orphan or cascade linked data.
  FOR v_fk IN
    SELECT c.conrelid, c.conkey, c.confkey
    FROM pg_catalog.pg_constraint c
    WHERE c.contype = 'f'
      AND c.confrelid = 'public.pelak_dealer_profiles'::regclass
  LOOP
    IF cardinality(v_fk.conkey) <> 1 OR cardinality(v_fk.confkey) <> 1 THEN
      RAISE EXCEPTION 'Complex relationship exists; deletion requires manual review';
    END IF;

    SELECT attname INTO v_column
    FROM pg_catalog.pg_attribute
    WHERE attrelid = v_fk.conrelid AND attnum = v_fk.conkey[1];

    EXECUTE format(
      'SELECT EXISTS (SELECT 1 FROM %s WHERE %I = $1)',
      v_fk.conrelid::regclass, v_column
    ) INTO v_has_links USING p_user_id;

    IF v_has_links THEN
      RAISE EXCEPTION 'Showroom has linked records in %; deletion blocked',
        v_fk.conrelid::regclass;
    END IF;
  END LOOP;

  -- Also check known dealer_id references that may lack FKs.
  FOR v_table IN
    SELECT DISTINCT c.table_schema, c.table_name
    FROM information_schema.columns c
    WHERE c.table_schema = 'public'
      AND c.column_name = 'dealer_id'
      AND c.table_name LIKE 'pelak_dealer_%'
  LOOP
    EXECUTE format(
      'SELECT EXISTS (SELECT 1 FROM %I.%I WHERE dealer_id = $1)',
      v_table.table_schema, v_table.table_name
    ) INTO v_has_links USING p_user_id;

    IF v_has_links THEN
      RAISE EXCEPTION 'Showroom has dependent records in %.%; deletion blocked',
        v_table.table_schema, v_table.table_name;
    END IF;
  END LOOP;

  DELETE FROM public.pelak_dealer_profiles WHERE user_id = p_user_id;
  RETURN FOUND;
END;
$fn$;

REVOKE ALL ON FUNCTION public.pelak_admin_showroom_delete(uuid,text)
FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.pelak_admin_showroom_delete(uuid,text)
TO authenticated;

COMMIT;

-- Safe installation checks:
-- SELECT to_regprocedure('public.pelak_admin_showroom_detail(uuid)');
-- SELECT to_regprocedure('public.pelak_admin_showroom_manage(uuid,text,text)');
-- SELECT to_regprocedure('public.pelak_admin_showroom_delete(uuid,text)');
-- Only call these actions in a signed-in super_admin session.
