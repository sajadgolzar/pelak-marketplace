create table if not exists public.pelak_listing_submissions (
 listing_id uuid primary key references public.listings(id) on delete cascade,
 user_id uuid not null references auth.users(id) on delete cascade,
 submitted_at timestamptz not null default now()
);
alter table public.pelak_listing_submissions enable row level security;
create index if not exists pelak_listing_submissions_user_idx on public.pelak_listing_submissions(user_id);
revoke all on public.pelak_listing_submissions from anon, authenticated;
grant select on public.pelak_listing_submissions to authenticated;
create policy pelak_submission_read on public.pelak_listing_submissions for select to authenticated using (
 user_id=(select auth.uid()) or exists(select 1 from public.admin_roles a where a.user_id=(select auth.uid()) and a.role in ('super_admin','admin','moderator'))
);
create or replace function public.pelak_submit_draft_for_review(p_listing_id uuid) returns boolean
language plpgsql security definer set search_path='' as $$
declare item public.listings%rowtype;
begin
 if auth.uid() is null then raise exception 'Login required'; end if;
 select * into item from public.listings where id=p_listing_id and user_id=auth.uid() for update;
 if not found or item.listing_status<>'draft' then raise exception 'Owned draft required'; end if;
 if coalesce(cardinality(item.image_paths),0) not between 1 and 8
 or coalesce(item.seller_phone,'') !~ '^\+[1-9][0-9]{7,14}$'
 or length(btrim(coalesce(item.title,'')))=0
 or length(btrim(coalesce(item.make,'')))=0
 or length(btrim(coalesce(item.model,'')))=0 then raise exception 'Complete listing and photos first'; end if;
 insert into public.pelak_listing_submissions(listing_id,user_id) values(item.id,auth.uid())
 on conflict(listing_id) do nothing;
 insert into public.pelak_push_events(recipient_id,kind,source_id)
 select a.user_id,'new_draft',item.id from public.admin_roles a where a.role='super_admin' and a.user_id<>auth.uid()
 on conflict(recipient_id,kind,source_id) do nothing;
 return true;
end $$;
revoke all on function public.pelak_submit_draft_for_review(uuid) from public,anon;
grant execute on function public.pelak_submit_draft_for_review(uuid) to authenticated;