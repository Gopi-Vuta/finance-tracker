import test from 'node:test';
import assert from 'node:assert/strict';

const response=(body,status=200)=>new Response(JSON.stringify(body),{status,headers:{'Content-Type':'application/json'}});
test('writes obtain a session CSRF token and serialize only the supplied payload',async()=>{
 const {request}=await import('../src/api.js?write');const calls=[];const original=globalThis.fetch;
 globalThis.fetch=async(url,options)=>{calls.push({url,options});return url==='/api/csrf'?response({token:'masked-session-token',headerName:'X-CSRF-TOKEN'}):response({revision:1});};
 try{assert.deepEqual(await request('/api/finance',{method:'PUT',body:{revision:0}}),{revision:1});assert.equal(calls.length,2);assert.equal(calls[1].options.credentials,'same-origin');assert.equal(calls[1].options.headers['X-CSRF-TOKEN'],'masked-session-token');assert.equal(calls[1].options.body,'{"revision":0}');}finally{globalThis.fetch=original;}
});
test('stale-save response remains a surfaced conflict, not a success',async()=>{
 const {request}=await import('../src/api.js?conflict');const original=globalThis.fetch;
 globalThis.fetch=async url=>url==='/api/csrf'?response({token:'t',headerName:'X-CSRF-TOKEN'}):response({message:'Data changed in another session.'},409);
 try{await assert.rejects(request('/api/finance',{method:'PUT',body:{}}),e=>e.status===409&&e.message.includes('another session'));}finally{globalThis.fetch=original;}
});
test('authentication expiry clears CSRF so a subsequent write obtains a new token',async()=>{
 const {request}=await import('../src/api.js?expiry');const original=globalThis.fetch;let csrfCalls=0,writes=0;
 globalThis.fetch=async url=>{if(url==='/api/csrf'){csrfCalls++;return response({token:String(csrfCalls),headerName:'X-CSRF-TOKEN'});}writes++;return writes===1?response({message:'Sign in to continue.'},401):response({ok:true});};
 try{await assert.rejects(request('/api/finance',{method:'PUT',body:{}}),e=>e.status===401);await request('/api/finance',{method:'PUT',body:{}});assert.equal(csrfCalls,2);}finally{globalThis.fetch=original;}
});
