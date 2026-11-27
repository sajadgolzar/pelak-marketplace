import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
const source = await readFile(new URL('../functions/api/native-photo-upload.js',import.meta.url),'utf8');
const {handle} = await import('data:text/javascript;base64,'+Buffer.from(source).toString('base64'));
const owner='11111111-1111-4111-8111-111111111111', photo='22222222-2222-4222-8222-222222222222';
const base='https://pelakauto.ir/api/native-photo-upload?owner='+owner+'&photo='+photo;
const jwt='Bearer e30.'+Buffer.from(JSON.stringify({role:'authenticated',sub:owner})).toString('base64url')+'.test';
const bytes=new Uint8Array([255,216,255,1,2,3]);
const req=(url=base,auth=jwt,body=bytes)=>new Request(url,{method:'POST',headers:{Authorization:auth,'Content-Type':'image/jpeg'},body});
test('rejects unauthenticated, mismatched owner and invalid body before upstream',async()=>{
  const fail=()=>{throw Error('must not fetch')};
  assert.equal((await handle(req(base,''),fail)).status,401);
  assert.equal((await handle(req(base.replace(owner,photo)),fail)).status,401);
  assert.equal((await handle(req(base,jwt,new Uint8Array([1,2,3])),fail)).status,400);
});
test('forwards only fixed path with user authorization and no redirects',async()=>{
  let called=false;
  const r=await handle(req(),async (url,options)=>{
    called=true; assert.equal(url,'https://wzhnkciopeqdbnbknwun.supabase.co/storage/v1/object/car-images/'+owner+'/'+photo+'.jpg');
    assert.equal(options.headers.Authorization,jwt); assert.equal(options.headers['x-upsert'],'false');
    assert.equal(options.redirect,'error'); assert.deepEqual(options.body,bytes);
    return new Response('{}',{status:200});
  });
  assert.equal(called,true); assert.equal(r.status,200); assert.equal(r.headers.get('Cache-Control'),'private, no-store');
});
test('recovery requires exact filename and byte count',async()=>{
  const q=new Request(base+'&size=6',{headers:{Authorization:jwt}});
  const found=await handle(q,async()=>Response.json([{name:photo+'.jpg',metadata:{size:6}}]));
  const wrong=await handle(q,async()=>Response.json([{name:photo+'.jpg',metadata:{size:5}}]));
  assert.equal(found.status,200);assert.equal(wrong.status,404);
});
test('upstream auth rejection is not treated as success',async()=>{
  const r=await handle(req(),async()=>new Response('{}',{status:403}));assert.equal(r.status,403);
});
