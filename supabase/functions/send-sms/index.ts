
import { Webhook } from "https://esm.sh/standardwebhooks@1.0.0";

Deno.serve(async (req) => {
  const reply = (data: unknown, status = 200) =>
    new Response(JSON.stringify(data), {
      status,
      headers: { "Content-Type": "application/json" },
    });

  if (req.method !== "POST") {
    return reply({ error: "Method not allowed" }, 405);
  }

  const apiKey = Deno.env.get("KAVENEGAR_API_KEY");
  const hookSecret = Deno.env.get("SEND_SMS_HOOK_SECRET");

  if (!apiKey || !hookSecret) {
    return reply({ error: "SMS not configured" }, 500);
  }

  let event: {
    user?: { phone?: string };
    sms?: { otp?: string };
  };

  try {
    const payload = await req.text();
    const webhook = new Webhook(
      hookSecret.replace(/^v1,whsec_/, "")
    );

    event = webhook.verify(
      payload,
      Object.fromEntries(req.headers)
    ) as typeof event;
  } catch {
    return reply({ error: "Invalid signature" }, 401);
  }

  const phone = event.user?.phone ?? "";
  const otp = event.sms?.otp ?? "";

  if (!/^\+989\d{9}$/.test(phone) || !/^\d{4,8}$/.test(otp)) {
    return reply({ error: "Invalid phone or code" }, 400);
  }

  const params = new URLSearchParams({
    receptor: "0" + phone.slice(3),
    token: otp,
    template: "pelaklogin",
    type: "sms",
  });

  try {
    const response = await fetch(
      `https://api.kavenegar.com/v1/${encodeURIComponent(apiKey)}/verify/lookup.json`,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/x-www-form-urlencoded",
        },
        body: params,
      }
    );

    const result = await response.json();

    if (!response.ok || result?.return?.status !== 200) {
      return reply({ error: "SMS provider failed" }, 502);
    }

    return reply({});
  } catch {
    return reply({ error: "SMS unavailable" }, 502);
  }
});
