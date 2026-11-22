create function pelak_operations.validate_listing_submission()
returns trigger language plpgsql security definer set search_path = ''
as $$
declare item record;
begin
  select l.city, l.seller_phone into item from public.listings l
  where l.id = new.listing_id;
  if not found then
    raise exception 'آگهی برای بررسی پیدا نشد.' using errcode = '23503';
  end if;
  if coalesce(item.city, '') !~ '[^[:space:]]' then
    raise exception 'برای ارسال آگهی به بررسی، شهر را وارد کنید.' using errcode = '23514';
  end if;
  if coalesce(item.seller_phone, '') !~ '^[+][1-9][0-9]{7,14}$' then
    raise exception 'برای ارسال آگهی به بررسی، شماره تماس معتبر وارد کنید.' using errcode = '23514';
  end if;
  return new;
end;
$$;
revoke all on function pelak_operations.validate_listing_submission() from public, anon, authenticated;
create trigger pelak_operations_validate_listing_submission
before insert on public.pelak_listing_submissions
for each row execute function pelak_operations.validate_listing_submission();
