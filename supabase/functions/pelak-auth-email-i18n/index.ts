// PELAK multilingual authentication emails — staging only.
// Enable Supabase Send Email Hook only after secrets, sender domain, and delivery tests are verified.
// Locale comes from user metadata; never use this value for authorization.
import { Webhook } from "https://esm.sh/standardwebhooks@1.0.0";

import { localeOf, render, copy } from "./templates.ts";

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
