-- PELAK / admin showroom inventory (READ-ONLY)
-- Run manually in Supabase SQL Editor after reviewing and backing up.
-- The GitHub pull request does not execute this SQL.
-- Returns all showroom profiles, including approved/private/free-disabled.
-- Never updates approvals, public visibility, profile ownership or subscriptions.

BEGIN;

CREATE OR REPLACE FUNCTION public.pelak_admin_showrooms_list()
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path TO ''
AS $function$
DECLARE
  v_role text;
  v_result jsonb;
BEGIN
  SELECT a.role
  INTO v_role
  FROM public.admin_roles a
  WHERE a.user_id = (SELECT auth.uid())
  LIMIT 1;

  -- Owner-only: includes account emails and non-public dealer records.
  IF v_role IS DISTINCT FROM 'super_admin' THEN
    RAISE EXCEPTION 'Owner access required'
      USING ERRCODE = '42501';
  END IF;

  SELECT COALESCE(
    jsonb_agg(
      jsonb_build_object(
        'user_id', p.user_id,
        'business_name', p.business_name,
        'city', p.city,
        'status', p.status,
        'free_profile_public', p.free_profile_public,
        'slug', p.slug,
        'owner_email', u.email,
        'created_at', p.created_at,
        'updated_at', p.updated_at
      )
      ORDER BY p.created_at DESC
    ), '[]'::jsonb
  ) INTO v_result
  FROM (
    SELECT user_id,business_name,city,status,free_profile_public,
           slug,created_at,updated_at
    FROM public.pelak_dealer_profiles
    ORDER BY created_at DESC
    LIMIT 500
  ) p
  LEFT JOIN auth.users u ON u.id = p.user_id;

  RETURN v_result;
END;
$function$;

REVOKE ALL ON FUNCTION public.pelak_admin_showrooms_list()
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.pelak_admin_showrooms_list()
  TO authenticated;

COMMIT;

-- Read-only presence check, after installing:
-- SELECT to_regprocedure('public.pelak_admin_showrooms_list()');
-- Call through an authenticated super_admin session from the site.
