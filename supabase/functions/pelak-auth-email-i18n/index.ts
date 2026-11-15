// PELAK multilingual authentication emails — staging only.
// Enable Supabase Send Email Hook only after secrets, sender domain, and delivery tests are verified.
// Locale comes from user metadata; never use this value for authorization.
import { Webhook } from "https://esm.sh/standardwebhooks@1.0.0";

type Locale = "fa" | "en" | "ar" | "ka";
type Copy = { subject: string; heading: string; intro: string; caution: string };
const copy: Record<Locale, Copy> = {
  fa: { subject: "کد تأیید پلاک | PELAK", heading: "تأیید ورود به پلاک", intro: "کد یک‌بارمصرف شما:", caution: "اگر درخواست نداده‌اید، این پیام را نادیده بگیرید." },
  en: { subject: "PELAK verification code", heading: "Verify your PELAK sign-in", intro: "Your one-time verification code:", caution: "If you did not request this, ignore this email." },
  ar: { subject: "رمز التحقق من بلاك | PELAK", heading: "تأكيد الدخول إلى بلاك", intro: "رمز التحقق لمرة واحدة:", caution: "إذا لم تطلب هذا الرمز، فتجاهل الرسالة." },
  ka: { subject: "PELAK-ის დამადასტურებელი კოდი", heading: "PELAK-ში შესვლის დადასტურება", intro: "თქვენი ერთჯერადი კოდი:", caution: "თუ ეს კოდი არ მოგითხოვიათ, უგულებელყავით წერილი." },
};
const supported = new Set<Locale>(["fa", "en", "ar", "ka"]);
function localeOf(input: unknown): Locale {
  const candidate = typeof input === "string" ? input.toLowerCase().split(/[-_]/)[0] : "";
  return supported.has(candidate as Locale) ? candidate as Locale : "en";
}
function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, ch => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[ch]!));
}
function render(locale: Locale, token: string): string {
  const t = copy[locale];
  const dir = locale === "fa" || locale === "ar" ? "rtl" : "ltr";
  return `<!doctype html><html lang="${locale}" dir="${dir}"><head><meta charset="utf-8"></head><body style="font-family:Arial,sans-serif;max-width:540px;margin:24px auto;padding:20px;color:#16263d"><h2>${escapeHtml(t.heading)}</h2><p>${escapeHtml(t.intro)}</p><p dir="ltr" style="font-size:34px;letter-spacing:6px;font-weight:bold;text-align:center">${escapeHtml(token)}</p><p>${escapeHtml(t.caution)}</p><hr><small>PELAK · pelakauto.com</small></body></html>`;
}
Deno.serve(async req => {
  if (req.method !== "POST") return new Response("Method not allowed", { status: 405 });
  const secret = Deno.env.get("SEND_EMAIL_HOOK_SECRET");
  const apiKey = Deno.env.get("RESEND_API_KEY");
  const sender = Deno.env.get("PELAK_AUTH_EMAIL_FROM");
  if (!secret || !apiKey || !sender) return new Response("Email service not configured", { status: 503 });
  let payload: { user?: { email?: string; user_metadata?: Record<string, unknown> }; email_data?: { token?: string; email_action_type?: string } };
  try {
    payload = new Webhook(secret.replace(/^v1,whsec_/, "")).verify(
      await req.text(), Object.fromEntries(req.headers)
    ) as typeof payload;
  } catch {
    return new Response("Invalid hook signature", { status: 401 });
  }
  const recipient = payload.user?.email;
  const token = payload.email_data?.token;
  // This first version supports OTP-based emails only. Do not activate for password resets
  // and other Auth actions until all action-specific flows have been implemented.
  if (!recipient || !token || !/^[0-9]{6,8}$/.test(token) ||
      !["magiclink", "signup", "email"].includes(payload.email_data?.email_action_type ?? "")) {
    return new Response("Unsupported email action", { status: 422 });
  }
  const locale = localeOf(payload.user?.user_metadata?.pelak_locale ?? payload.user?.user_metadata?.locale);
  const result = await fetch("https://api.resend.com/emails", {
    method: "POST",
    headers: { "Authorization": `Bearer ${apiKey}`, "Content-Type": "application/json" },
    body: JSON.stringify({ from: sender, to: [recipient], subject: copy[locale].subject, html: render(locale, token) }),
  });
  if (!result.ok) {
    // Avoid logging recipient addresses, OTPs, or provider response bodies.
    return new Response("Email delivery failed", { status: 502 });
  }
  return new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } });
});
