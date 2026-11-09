// PELAK — native Android FCM worker; server-side only.
// Deploy with Supabase Edge Functions. Invoke with Authorization: Bearer <JOB_SECRET>
// after configuring FIREBASE_SERVICE_ACCOUNT_JSON, SUPABASE_SERVICE_ROLE_KEY, etc.
// NO secrets belong in website JS, Android APK or GitHub commits.
import { createClient } from "npm:@supabase/supabase-js@2.49.4";

const enc=new TextEncoder();
function base64url(bytes:Uint8Array):string{
  let input="";
  for(const b of bytes)input+=String.fromCharCode(b);
  return btoa(input).replace(/\+/g,"-").replace(/\//g,"_").replace(/=+$/,"");
}
function encodePayload(value:unknown):string{return base64url(enc.encode(JSON.stringify(value)));}
async function getGoogleAccessToken(account:{client_email:string;private_key:string}):Promise<string>{
  const now=Math.floor(Date.now()/1000);
  const jwtHead=encodePayload({alg:"RS256",typ:"JWT"});
  const jwtBody=encodePayload({
    iss:account.client_email,
    scope:"https://www.googleapis.com/auth/firebase.messaging",
    aud:"https://oauth2.googleapis.com/token",
    iat:now,exp:now+3500,
  });
  const unsigned=jwtHead+"."+jwtBody;
  const pem=account.private_key.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g,"");
  const data=Uint8Array.from(atob(pem),char=>char.charCodeAt(0));
  const key=await crypto.subtle.importKey("pkcs8",data,{name:"RSASSA-PKCS1-v1_5",hash:"SHA-256"},false,["sign"]);
  const signature=new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5",key,enc.encode(unsigned)));
  const jwt=unsigned+"."+base64url(signature);
  const response=await fetch("https://oauth2.googleapis.com/token",{
    method:"POST",
    headers:{"content-type":"application/x-www-form-urlencoded"},
    body:new URLSearchParams({
      grant_type:"urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion:jwt,
    }),
  });
  if(!response.ok)throw Error("Firebase OAuth token request failed");
  const result=await response.json();
  if(!result.access_token)throw Error("Firebase OAuth response had no access token");
  return String(result.access_token);
}

function label(kind:string):{title:string;body:string}{
  const messages:Record<string,{title:string;body:string}>={
    listing_needs_revision:{title:"آگهی نیازمند اصلاح است",body:"دلیل برگشت را در بخش آگهی‌های من ببینید."},
    listing_published:{title:"آگهی شما منتشر شد",body:"خودروی شما در فهرست آگهی‌های پلاک قرار گرفت."},
    listing_report:{title:"گزارش جدید آگهی",body:"گزارش تازه‌ای در پنل مدیریت پلاک دریافت شد."},
    support_message:{title:"پیام جدید پشتیبانی",body:"برای مشاهده پیام وارد پلاک شوید."},
    listing_message:{title:"پیام جدید درباره خودرو",body:"برای پاسخ وارد پیام‌های پلاک شوید."},
    new_draft:{title:"آگهی جدید برای بررسی",body:"پیش‌نویس جدیدی در پنل مدیریت ثبت شد."},
  };
  return messages[kind]||{title:"اعلان جدید پلاک",body:"برای مشاهده جزئیات وارد حساب پلاک شوید."};
}
function json(status:number,body:unknown){
  return new Response(JSON.stringify(body),{
    status,headers:{"content-type":"application/json;charset=utf-8","cache-control":"no-store"}
  });
}

Deno.serve(async request=>{
  if(request.method!=="POST")return json(405,{error:"Method not allowed"});
  const jobSecret=Deno.env.get("PELAK_ANDROID_PUSH_JOB_SECRET");
  if(!jobSecret||request.headers.get("authorization")!=="Bearer "+jobSecret)
    return json(401,{error:"Unauthorized"});
  const apiUrl=Deno.env.get("SUPABASE_URL");
  const serviceKey=Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  const projectId=Deno.env.get("PELAK_FIREBASE_PROJECT_ID");
  const rawAccount=Deno.env.get("PELAK_FIREBASE_SERVICE_ACCOUNT_JSON");
  if(!apiUrl||!serviceKey||!projectId||!rawAccount)
    return json(503,{error:"Native push service has not been configured"});
  let account:{client_email:string;private_key:string};
  try{account=JSON.parse(rawAccount)}catch(_){return json(503,{error:"Invalid Firebase service configuration"})}
  const client=createClient(apiUrl,serviceKey,{auth:{persistSession:false,autoRefreshToken:false}});
  try{
    const token=await getGoogleAccessToken(account);
    const {data:jobs,error:claimError}=await client.rpc("pelak_android_push_claim",{p_limit:25});
    if(claimError)throw Error("Could not claim notification queue");
    let processed=0,failed=0;
    for(const job of jobs||[]){
      let errorText="";
      try{
        const {data:devices,error:deviceError}=await client.from("pelak_android_push_tokens")
          .select("token").eq("user_id",job.recipient_id).limit(15);
        if(deviceError)throw Error("Device lookup failed");
        for(const device of devices||[]){
          const notification=label(String(job.kind||"general"));
          const fcm=await fetch(
            "https://fcm.googleapis.com/v1/projects/"+encodeURIComponent(projectId)+"/messages:send",{
              method:"POST",
              headers:{"authorization":"Bearer "+token,"content-type":"application/json"},
              body:JSON.stringify({
                message:{
                  token:device.token,
                  notification,
                  android:{notification:{channel_id:"pelak_updates"}},
                  data:{kind:String(job.kind||"general"),source_id:String(job.source_id||"")}
                }
              })
            }
          );
          if(!fcm.ok){
            const body=await fcm.text();
            // Remove tokens that Firebase confirms are no longer valid.
            if(fcm.status===404||body.includes("UNREGISTERED")){
              await client.from("pelak_android_push_tokens").delete().eq("token",device.token);
            }else{
              errorText="FCM HTTP "+fcm.status; // Never log or return token data.
            }
          }
        }
      }catch(_){errorText="Native push worker delivery error"}
      if(errorText){
        failed++;
        await client.from("pelak_android_push_outbox").update({
          claimed_at:null,last_error:errorText.slice(0,180)
        }).eq("id",job.id);
      }else{
        processed++;
        await client.from("pelak_android_push_outbox").update({
          sent_at:new Date().toISOString(),claimed_at:null,last_error:null
        }).eq("id",job.id);
      }
    }
    return json(200,{ok:true,processed,failed});
  }catch(_){
    return json(502,{error:"FCM dispatch is unavailable; queue will retry"});
  }
});
