const ROOT = 'https://wzhnkciopeqdbnbknwun.supabase.co';
const KEY = "sb_publishable_e-2nFIThVyoQPcNivWiPRg_qZjnVY3d";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MAX = 6 * 1024 * 1024;
function json(status, body) {
  return new Response(JSON.stringify(body), { status, headers: {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'private, no-store',
    'X-Content-Type-Options': 'nosniff'
  }});
}
function matchesOwner(auth, owner) {
  // Unverified claims are used only to reject invalid requests early.
  // Supabase validates the signature and enforces the existing Storage RLS.
  if (!auth || auth.length > 8192 || !/^Bearer [A-Za-z0-9_.-]+$/.test(auth)) return false;
  try {
    const parts = auth.slice(7).split('.');
    if (parts.length !== 3) return false;
    const raw = parts[1].replace(/-/g,'+').replace(/_/g,'/');
    const claims = JSON.parse(atob(raw.padEnd(Math.ceil(raw.length/4)*4,'=')));
    return claims.role === 'authenticated' && claims.sub === owner;
  } catch { return false; }
}
async function readJpeg(request) {
  const size = request.headers.get('Content-Length');
  if (size !== null && (!/^\d+$/.test(size) || Number(size) > MAX)) return null;
  if (!request.body) return null;
  const reader = request.body.getReader(), chunks = [];
  let total = 0;
  try {
    while (true) {
      const {done, value} = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > MAX) { await reader.cancel(); return null; }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  if (total < 3 || (size !== null && Number(size) !== total)) return null;
  const bytes = new Uint8Array(total); let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  return bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255 ? bytes : null;
}
export async function handle(request, upstream = fetch) {
  const url = new URL(request.url);
  const owner = url.searchParams.get('owner'), photo = url.searchParams.get('photo');
  const auth = request.headers.get('Authorization');
  if (request.method !== 'POST' && request.method !== 'GET') return json(405,{error:'method'});
  if (!owner || !photo || !UUID.test(owner) || !UUID.test(photo)) return json(400,{error:'invalid_id'});
  if (!matchesOwner(auth, owner)) return json(401,{error:'auth'});
  const name = photo + '.jpg';
  const baseHeaders = {'Authorization':auth,'apikey':KEY,'Cache-Control':'no-cache'};
  const controller = new AbortController();
  const timer = setTimeout(()=>controller.abort(), 25000);
  try {
    if (request.method === 'POST') {
      if (request.headers.get('Content-Type') !== 'image/jpeg') return json(415,{error:'type'});
      const bytes = await readJpeg(request);
      if (!bytes) return json(400,{error:'invalid_image'});
      const resp = await upstream(ROOT + '/storage/v1/object/car-images/' + owner + '/' + name, {
        method:'POST', headers:{...baseHeaders,'Content-Type':'image/jpeg','x-upsert':'false'},
        body:bytes, redirect:'error', signal:controller.signal
      });
      if (resp.ok) return json(200,{ok:true});
      if (resp.status === 400 || resp.status === 409) {
        const msg = (await resp.text()).slice(0,8192);
        if (/duplicate|already.?exists/i.test(msg)) return json(409,{error:'exists'});
      }
      return json([400,401,403,409,413,429].includes(resp.status)?resp.status:502,{error:'upstream'});
    }
    const size = Number(url.searchParams.get('size'));
    if (!Number.isInteger(size) || size <= 0 || size > MAX) return json(400,{error:'size'});
    const resp = await upstream(ROOT + '/storage/v1/object/list/car-images', {
      method:'POST', headers:{...baseHeaders,'Content-Type':'application/json'},
      body:JSON.stringify({prefix:owner,search:name,limit:20}), redirect:'error',signal:controller.signal
    });
    if (!resp.ok) return json([401,403,429].includes(resp.status)?resp.status:502,{error:'upstream'});
    const rows = await resp.json();
    const found = Array.isArray(rows) && rows.some(r=>r?.name === name && Number(r?.metadata?.size) === size);
    return json(found?200:404,{exists:found});
  } catch { return json(502,{error:'connection'}); }
  finally {clearTimeout(timer);}
}
export function onRequest({request}) { return handle(request); }
