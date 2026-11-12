// PELAK — expired 7-day revision cleanup worker.
// NOT live until deployed AND invoked by an owner-configured scheduled job.
// Never put the service role key or worker secret in the repository / web app.
// Deploy with "Verify JWT" OFF; this function authenticates a private job secret.
import { createClient } from "npm:@supabase/supabase-js@2.49.4";

const BUCKET = "car-images";
const MAX_LIST_PAGES = 20; // 20 x 100 objects per ad at most
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

type Claim = {
  listing_id: string;
  owner_id: string;
  claim_token: string;
};

function reply(status: number, value: unknown): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: {
      "content-type": "application/json;charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

Deno.serve(async (req: Request): Promise<Response> => {
  if (req.method !== "POST") return reply(405, { error: "Method not allowed" });
  const workerSecret = Deno.env.get("PELAK_REVISION_CLEANUP_SECRET");
  if (!workerSecret || req.headers.get("authorization") !== "Bearer " + workerSecret) {
    return reply(401, { error: "Unauthorized" });
  }

  const url = Deno.env.get("SUPABASE_URL");
  const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !key) return reply(503, { error: "Supabase service configuration missing" });

  const db = createClient(url, key, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  let claims: Claim[];
  try {
    const { data, error } = await db.rpc("pelak_revision_cleanup_claim", { p_limit: 5 });
    if (error) throw error;
    if (!Array.isArray(data)) throw Error("Unexpected claim response");
    claims = data as Claim[];
  } catch (_) {
    return reply(502, { error: "Could not claim expired revisions" });
  }

  let completed = 0;
  let failed = 0;
  let removedFiles = 0;

  for (const job of claims) {
    try {
      if (!UUID.test(job.listing_id) || !UUID.test(job.owner_id) ||
          !UUID.test(job.claim_token)) {
        throw Error("Invalid cleanup job identifiers");
      }
      // Every image uploaded by PELAK uses owner-id/listing-id/uuid.jpg.
      // Never touch any other path or any other Storage bucket.
      const folder = job.owner_id + "/" + job.listing_id;

      let empty = false;
      for (let page = 0; page < MAX_LIST_PAGES; page++) {
        // Always page from offset 0: the previous batch was removed.
        const { data, error } = await db.storage.from(BUCKET).list(folder, {
          limit: 100,
          offset: 0,
        });
        if (error) throw Error("Storage listing failed");
        if (!Array.isArray(data)) throw Error("Invalid Storage listing response");

        if (data.length === 0) {
          empty = true;
          break;
        }

        const paths: string[] = [];
        for (const object of data) {
          const name = object.name;
          if (typeof name !== "string" || !name ||
              name === "." || name === ".." || name.includes("/")) {
            throw Error("Unsafe Storage object name");
          }
          // A folder entry indicates an unexpected nested path. Fail closed.
          if (!object.id) throw Error("Nested Storage folder needs manual review");
          paths.push(folder + "/" + name);
        }

        const { error: removeError } = await db.storage.from(BUCKET).remove(paths);
        if (removeError) throw Error("Storage deletion failed");
        removedFiles += paths.length;
      }

      if (!empty) {
        throw Error("Storage folder could not be emptied within safety limit");
      }

      // DB verifies the deadline, original review status, claim token,
      // archive reason, absence of shared files, and zero remaining objects.
      // If any FK blocks the delete, the listing is retained for inspection.
      const { data: done, error: finishError } = await db.rpc(
        "pelak_revision_cleanup_finish",
        { p_listing_id: job.listing_id, p_claim_token: job.claim_token },
      );
      if (finishError || done !== true) {
        throw Error("Database finalization failed: " + (finishError?.message || "not confirmed"));
      }
      completed++;
    } catch (error) {
      failed++;
      const message = error instanceof Error ? error.message : "Cleanup failed";
      // Delay retries and preserve a minimal error record, not private user data.
      try {
        await db.rpc("pelak_revision_cleanup_fail", {
          p_listing_id: job.listing_id,
          p_claim_token: job.claim_token,
          p_error: message.slice(0, 300),
        });
      } catch (_) {
        // A failed error report must never trigger unsafe fallback deletion.
      }
    }
  }

  return reply(200, {
    ok: failed === 0,
    claimed: claims.length,
    completed,
    failed,
    removedFiles,
  });
});
