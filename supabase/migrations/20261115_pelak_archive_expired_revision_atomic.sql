-- Archive a rejected draft atomically with its correction deadline check.
-- Lock order matches pelak_resubmit_rejected_listing: listing first, rejection second.
create or replace function public.pelak_archive_expired_revision(p_listing_id uuid)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_listing public.listings%rowtype;
  v_rejection public.pelak_listing_rejections%rowtype;
  v_now timestamptz;
begin
  if auth.role() <> 'service_role' then
    raise exception 'Access denied';
  end if;
  select * into v_listing from public.listings
    where id=p_listing_id for update;
  if not found or v_listing.listing_status <> 'draft' then
    return false;
  end if;
  select * into v_rejection from public.pelak_listing_rejections
    where listing_id=p_listing_id for update;
  v_now := clock_timestamp();
  if not found
     or v_rejection.owner_id is distinct from v_listing.user_id
     or v_rejection.review_state <> 'needs_revision'
     or v_rejection.delete_after is null
     or v_rejection.delete_after > v_now then
    return false;
  end if;
  update public.listings set
    listing_status='archived',archive_reason='revision_timeout',
    archived_at=v_now,purge_after=v_now,updated_at=v_now
    where id=p_listing_id;
  return true;
end;
$$;
revoke all on function public.pelak_archive_expired_revision(uuid) from public, anon, authenticated;
grant execute on function public.pelak_archive_expired_revision(uuid) to service_role;
