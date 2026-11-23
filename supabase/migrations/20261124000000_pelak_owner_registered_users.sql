create function public.pelak_owner_registered_users(p_offset integer default 0)
returns table(email text, phone text, registered_at timestamptz, has_listing boolean, has_draft boolean)
language plpgsql stable security definer set search_path = ''
as $$
begin
  if auth.uid() is null or not exists (
    select 1 from public.admin_roles where user_id = auth.uid() and role = 'super_admin'
  ) then
    raise exception 'Access denied' using errcode = '42501';
  end if;

  return query
  with page as (
    select u.id, u.email, u.phone, u.created_at from auth.users u
    where u.deleted_at is null and not coalesce(u.is_anonymous, false)
    order by u.created_at desc, u.id desc
    limit 51 offset greatest(0, coalesce(p_offset, 0))
  )
  select u.email::text,
    coalesce(nullif(u.phone, ''), (
      select nullif(trim(l.seller_phone), '') from public.listings l
      where l.user_id = u.id and nullif(trim(l.seller_phone), '') is not null
      order by l.updated_at desc nulls last, l.id desc limit 1
    ))::text,
    u.created_at,
    exists(select 1 from public.listings l where l.user_id = u.id),
    exists(select 1 from public.listings l where l.user_id = u.id and l.listing_status = 'draft')
  from page u
  order by u.created_at desc, u.id desc;
end;
$$;

revoke all on function public.pelak_owner_registered_users(integer) from public, anon, authenticated;
grant execute on function public.pelak_owner_registered_users(integer) to authenticated;
