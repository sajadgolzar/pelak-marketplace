import { assert, assertEquals, assertStringIncludes } from "https://deno.land/std@0.224.0/assert/mod.ts";
import { localeOf, render } from "./templates.ts";

Deno.test("locale normalization and English fallback", () => {
  assertEquals(localeOf("fa-IR"), "fa");
  assertEquals(localeOf("en-US"), "en");
  assertEquals(localeOf("ar-AE"), "ar");
  assertEquals(localeOf("ka-GE"), "ka");
  assertEquals(localeOf("de"), "en");
  assertEquals(localeOf(null), "en");
});
Deno.test("all languages render OTP and safe direction", () => {
  for (const lang of ["fa", "en", "ar", "ka"] as const) {
    const html = render(lang, "123456");
    assertStringIncludes(html, "123456");
    assertStringIncludes(html, `lang="${lang}"`);
    assertStringIncludes(html, `dir="${lang === "fa" || lang === "ar" ? "rtl" : "ltr"}"`);
  }
});
Deno.test("HTML-escape untrusted values", () => {
  const html = render("en", "<script>alert(1)</script>");
  assert(!html.includes("<script>"));
  assertStringIncludes(html, "&lt;script&gt;");
});
