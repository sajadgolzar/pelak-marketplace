// PELAK automatic retention worker. Runs only from a Vault-authenticated cron.
// Removes media through Storage API (never DELETE from storage.objects directly).
// Safeguards: expired archived/rejected only, folder ownership, shared-path check.
import { createClient } from "npm:@supabase/supabase-js@2.49.4";

const PROJECT_URL = Deno.env.get("SUPABASE_URL") || "";
function serviceKey(): string {
  const legacy=Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (legacy) return legacy;
  try {
    const obj=JSON.parse(Deno.env.get("SUPABASE_SECRET_KEYS")||"{}");
    return String(obj.default||"");
  } catch { return ""; }
}
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
function json(status:number,body:unknown){
  return new Response(JSON.stringify(body),{status,headers:{"content-type":"application/json;charset=utf-8","cache-control":"no-store"}});
}

Deno.serve(async req=>{
  if(req.method!=="POST") return json(405,{error:"method not allowed"});
  const token=req.headers.get("x-pelak-maintenance-token")||"";
  if(!/^[a-f0-9]{64}$/.test(token)) return json(401,{error:"unauthorized"});
  const key=serviceKey();
  if(!key||!PROJECT_URL)return json(503,{error:"worker not configured"});
  const db=createClient(PROJECT_URL,key,{auth:{persistSession:false,autoRefreshToken:false}});
  const auth=await db.rpc("pelak_media_cleanup_authorized",{p_token:token});
  if(auth.error||auth.data!==true)return json(401,{error:"unauthorized"});

  const now=new Date().toISOString();
  let archived=0,removed=0,retained=0,failed=0;
  try {
    // A seller only loses an uncorrected returned draft AFTER its correction deadline.
    // Do not touch published, sold, or resubmitted listings.
    const expired=await db.from("pelak_listing_rejections")
      .select("listing_id").eq("review_state","needs_revision")
      .lte("delete_after",now).limit(25);
    if(expired.error)throw Error("expired queue unavailable");
    for(const row of expired.data||[]){
      if(!uuid.test(row.listing_id))continue;
      const result=await db.from("listings").update({
        listing_status:"archived",archive_reason:"revision_timeout",
        archived_at:now,purge_after:now,updated_at:now
      }).eq("id",row.listing_id).eq("listing_status","draft").select("id");
      if(result.error){failed++;continue}
      archived+=(result.data||[]).length;
    }

    const due=await db.from("listings")
      .select("id,user_id,image_paths,purge_after,listing_status")
      .eq("listing_status","archived").lte("purge_after",now)
      .is("media_purged_at",null).order("purge_after",{ascending:true}).limit(20);
    if(due.error)throw Error("retention queue unavailable");
    for(const row of due.data||[]){
      try {
        if(!uuid.test(row.id)||!uuid.test(row.user_id)){retained++;continue}
        const prefix=row.user_id+"/"+row.id+"/";
        const referenced=Array.isArray(row.image_paths)?row.image_paths:[];
        if(referenced.some((p:unknown)=>typeof p!=="string"||!p.startsWith(prefix))){
          retained++;continue; // Never delete a path outside the listing's own folder.
        }
        const folder=prefix.slice(0,-1);
        const listing=await db.storage.from("car-images").list(folder,{limit:101,offset:0});
        if(listing.error){failed++;continue}
        const files=listing.data||[];
        if(files.length>100 || files.some((file)=>!file.id)){retained++;continue}
        const paths=[...new Set([...referenced,...files.map(file=>prefix+file.name)])];
        // Don't delete any file referenced by another listing.
        let shared=false;
        for(const p of paths){
          const check=await db.from("listings").select("id")
            .neq("id",row.id).contains("image_paths",[p]).limit(1);
          if(check.error){throw Error("shared-path check failed")}
          if((check.data||[]).length){shared=true;break}
        }
        if(shared){retained++;continue}
        if(paths.length){
          const deletion=await db.storage.from("car-images").remove(paths);
          if(deletion.error){failed++;continue}
        }
        const after=await db.storage.from("car-images").list(folder,{limit:1});
        if(after.error || (after.data||[]).length){failed++;continue}
        const marked=await db.from("listings").update({
          image_paths:[],media_purged_at:new Date().toISOString(),updated_at:new Date().toISOString()
        }).eq("id",row.id).eq("listing_status","archived").select("id");
        if(marked.error||(marked.data||[]).length!==1){failed++;continue}

        // Foreign-key RESTRICT protects conversations: keep the record if messages exist.
        const messages=await db.from("pelak_listing_messages")
          .select("id",{count:"exact",head:true}).eq("listing_id",row.id);
        if(messages.error || (messages.count||0)>0){retained++;continue}
        const deleted=await db.from("listings").delete()
          .eq("id",row.id).eq("listing_status","archived").select("id");
        if(deleted.error||(deleted.data||[]).length!==1){retained++;continue}
        removed++;
      }catch(_){failed++}
    }
    return json(200,{ok:true,archived,removed,retained,failed});
  }catch(_){
    return json(500,{error:"retention service unavailable",archived,removed,retained,failed});
  }
});
