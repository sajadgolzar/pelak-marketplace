type Locale = "fa" | "en" | "ar" | "ka";
type Copy = { subject: string; heading: string; intro: string; caution: string };
export const copy: Record<Locale, Copy> = {
  fa: { subject: "کد تأیید پلاک | PELAK", heading: "تأیید ورود به پلاک", intro: "کد یک‌بارمصرف شما:", caution: "اگر درخواست نداده‌اید، این پیام را نادیده بگیرید." },
  en: { subject: "PELAK verification code", heading: "Verify your PELAK sign-in", intro: "Your one-time verification code:", caution: "If you did not request this, ignore this email." },
  ar: { subject: "رمز التحقق من بلاك | PELAK", heading: "تأكيد الدخول إلى بلاك", intro: "رمز التحقق لمرة واحدة:", caution: "إذا لم تطلب هذا الرمز، فتجاهل الرسالة." },
  ka: { subject: "PELAK-ის დამადასტურებელი კოდი", heading: "PELAK-ში შესვლის დადასტურება", intro: "თქვენი ერთჯერადი კოდი:", caution: "თუ ეს კოდი არ მოგითხოვიათ, უგულებელყავით წერილი." },
};
const supported = new Set<Locale>(["fa", "en", "ar", "ka"]);
export function localeOf(input: unknown): Locale {
  const candidate = typeof input === "string" ? input.toLowerCase().split(/[-_]/)[0] : "";
  return supported.has(candidate as Locale) ? candidate as Locale : "en";
}
function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, ch => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[ch]!));
}
export function render(locale: Locale, token: string): string {
  const t = copy[locale];
  const dir = locale === "fa" || locale === "ar" ? "rtl" : "ltr";
  return `<!doctype html><html lang="${locale}" dir="${dir}"><head><meta charset="utf-8"></head><body style="font-family:Arial,sans-serif;max-width:540px;margin:24px auto;padding:20px;color:#16263d"><h2>${escapeHtml(t.heading)}</h2><p>${escapeHtml(t.intro)}</p><p dir="ltr" style="font-size:34px;letter-spacing:6px;font-weight:bold;text-align:center">${escapeHtml(token)}</p><p>${escapeHtml(t.caution)}</p><hr><small>PELAK · pelakauto.com</small></body></html>`;
}
