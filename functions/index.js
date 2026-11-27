const supabase = "https://wzhnkciopeqdbnbknwun.supabase.co";
const publicKey = "sb_publishable_e-2nFIThVyoQPcNivWiPRg_qZjnVY3d";
const uuid = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i;
const escapeHtml = value => String(value).replace(/[&<>"']/g, char =>
  ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[char]);

export async function onRequestGet({ request, next }) {
  const url = new URL(request.url);
  const id = url.searchParams.get("listing");
  if (!uuid.test(id ?? "")) return next();
  try {
    const endpoint = new URL("/rest/v1/listings", supabase);
    endpoint.search = new URLSearchParams({
      id: "eq." + id, listing_status: "eq.published",
      select: "make,model,city,image_paths", limit: "1",
    });
    const headers = { apikey: publicKey };
    const response = await fetch(endpoint, { headers, signal: AbortSignal.timeout(2500) });
    if (!response.ok) return next();
    const [listing] = await response.json();
    if (!listing) return next();

    const title = [listing.make, listing.model].filter(Boolean).join(" ") || "آگهی خودرو";
    const tags = [
      ["og:title", title + " | پلاک"],
      ["og:description", listing.city ? "آگهی خودرو در " + listing.city : "مشاهدهٔ آگهی خودرو در پلاک"],
      ["og:url", "https://pelakauto.ir/?listing=" + id],
      ["og:type", "website"],
    ];
    const path = listing.image_paths?.[0];
    if (typeof path === "string") {
      try {
        const object = path.split("/").map(encodeURIComponent).join("/");
        const photo = await fetch(supabase + "/storage/v1/object/sign/car-images/" + object, {
          method: "POST", headers: { ...headers, "Content-Type": "application/json" },
          body: JSON.stringify({ expiresIn: 86400 }), signal: AbortSignal.timeout(2500),
        });
        if (photo.ok) {
          const { signedURL } = await photo.json();
          if (signedURL?.startsWith("/object/sign/car-images/")) {
            tags.push(["og:image", supabase + "/storage/v1" + signedURL]);
          }
        }
      } catch { /* The listing metadata remains usable without a photo. */ }
    }
    const page = await next();
    if (!page.ok || !page.headers.get("content-type")?.includes("text/html")) return page;
    // The homepage now has default Open Graph tags. Replace those on listing links.
    const rewriter = new HTMLRewriter();
    for (const [key] of tags) {
      rewriter.on('meta[property="' + key + '"]', { element(meta) { meta.remove(); } });
    }
    const result = rewriter.on("link[rel=canonical]", {
      element(link) { link.setAttribute("href", "https://pelakauto.ir/?listing=" + id); }
    }).on("head", {
      element(head) {
        head.prepend(tags.map(([key, value]) =>
          '<meta property="' + key + '" content="' + escapeHtml(value) + '">'
        ).join(""), { html: true });
      },
    }).transform(page);
    result.headers.set("Cache-Control", "no-store");
    return result;
  } catch {
    return next();
  }
}
