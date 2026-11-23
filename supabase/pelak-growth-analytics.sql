create table public.pelak_growth_events (
  event text not null check (event in ('ios_guide','ios_launch','android_launch','share_open','share_return')),
  visitor uuid not null,
  day date not null default ((now() at time zone 'Asia/Tehran')::date),
  primary key(event,visitor,day)
);
alter table public.pelak_growth_events enable row level security;
revoke all on public.pelak_growth_events from public, anon, authenticated;

create function public.pelak_growth_record(p_event text,p_visitor uuid)
returns void language sql security definer set search_path = '' as $$
  insert into public.pelak_growth_events(event,visitor)
  select p_event,p_visitor
  where p_visitor is not null and p_event in ('ios_guide','ios_launch','android_launch','share_open','share_return')
  on conflict do nothing
$$;
revoke all on function public.pelak_growth_record(text,uuid) from public;
grant execute on function public.pelak_growth_record(text,uuid) to anon, authenticated;

create function public.pelak_growth_counts()
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare today date := (now() at time zone 'Asia/Tehran')::date;
begin
  if not exists (select 1 from public.admin_roles where user_id=auth.uid() and role='super_admin') then
    raise exception 'Access denied' using errcode='42501';
  end if;
  return (
    with cohort as (
      select visitor,min(day) as first_day from public.pelak_growth_events
      where event='share_open' group by visitor
    ), totals as (
      select event,count(distinct visitor) as n from public.pelak_growth_events
      where day between today-29 and today group by event
    )
    select coalesce((select jsonb_object_agg(event,n) from totals),'{}'::jsonb)
      || jsonb_build_object('share_cohort',count(*),'share_returned',count(*) filter(where exists (
        select 1 from public.pelak_growth_events e where e.visitor=c.visitor and e.event='share_return'
        and e.day between c.first_day+1 and c.first_day+7
      )))
    from cohort c where c.first_day between today-36 and today-8
  );
end $$;
revoke all on function public.pelak_growth_counts() from public,anon;
grant execute on function public.pelak_growth_counts() to authenticated;
