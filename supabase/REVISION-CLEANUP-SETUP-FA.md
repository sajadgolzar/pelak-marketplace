# پاک‌سازی خودکار آگهی برگشتی پلاک — آماده، هنوز غیرفعال

## رفتار
- فقط آگهی‌های با review_state = needs_revision و delete_after گذشته.
- آگهی باید پیش‌نویس باشد، یا قبلاً با archive_reason = revision_expired_cleanup برای پاک‌سازی قفل شده باشد.
- آگهی اصلاح و ارسال مجدد شده حذف نمی‌شود.
- فایل‌های پوشه اختصاصی همان آگهی در bucket خصوصی car-images از طریق Storage API پاک می‌شوند.
- سپس رکورد آگهی حذف می‌شود؛ اگر یک کلید خارجی حذف را ممنوع کند، خطا ثبت می‌شود و حذف دیتابیس کامل نمی‌شود.
- مسیر نامعتبر یا پوشه مشترک: توقف ایمن و ثبت خطا.
- این سازوکار هیچ‌یک از اختیارات حذف دستی مدیر اصلی را تغییر نمی‌دهد.

## پیش از نصب
1. بررسی فقط‌خواندنی PELAK-REVISION-CLEANUP-PREFLIGHT-20261112.sql را اجرا کنید.
2. کلیدهای خارجی ON DELETE را بررسی کنید؛ هر NO ACTION یا RESTRICT ممکن است مانع حذف نهایی شود.
3. مسیرهای نامتعارف عکس را جداگانه بررسی کنید.
4. از دیتابیس و Storage نسخه پشتیبان واقعی بگیرید. حذف تصاویر بازگشت‌ناپذیر است.

## نصب بدون فعال‌کردن زمان‌بندی
1. migration با نام pelak-revision-expiry-cleanup-20261112.sql را در Supabase SQL Editor اجرا کنید؛ به تنهایی هیچ داده‌ای پاک نمی‌کند.
2. فایل functions/pelak-revision-expiry-cleanup/index.ts را با نام pelak-revision-expiry-cleanup به Edge Functions منتقل و Deploy کنید.
3. برای همین تابع Verify JWT را OFF کنید؛ خود تابع فقط درخواست با secret اختصاصی را می‌پذیرد.
4. در Edge Function Secrets یک مقدار تصادفی محرمانه با نام PELAK_REVISION_CLEANUP_SECRET ثبت کنید. هرگز آن را در سایت، GitHub یا چت نگذارید.
5. SUPABASE_URL و SUPABASE_SERVICE_ROLE_KEY فقط در سمت سرور موجود باشند.
6. قبل از Cron با داده‌های آزمایشی آزمون کنید: آگهی سالم/ارسال مجدد محفوظ و آگهی منقضی با تمام فایل‌های مخصوصش حذف شود.

## زمان‌بندی بعد از تست
- در Supabase Dashboard > Integrations > Cron با pg_cron و pg_net یک HTTP POST هر ۱۵ دقیقه به آدرس زیر تنظیم کنید:
  https://PROJECT_REF.supabase.co/functions/v1/pelak-revision-expiry-cleanup
- Header: Authorization: Bearer [PRIVATE_WORKER_SECRET]
- Header: Content-Type: application/json
- Body: {}
- URL و secret را در Supabase Vault نگه دارید، نه داخل GitHub.
- وضعیت نمونه: {"ok":true,"claimed":1,"completed":1,"failed":0,"removedFiles":3}
- این ریپو هیچ Cron job را فعال نمی‌کند.

## کنترل خطا
SELECT listing_id, attempt_count, completed_at, last_error, updated_at
FROM public.pelak_revision_cleanup_jobs
ORDER BY updated_at DESC LIMIT 30;

## توقف اضطراری
Cron job را در Supabase غیرفعال کنید. حذف‌های انجام شده بدون بکاپ برگشت‌پذیر نیستند.
