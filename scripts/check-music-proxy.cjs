const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const source=fs.readFileSync('dist/app.js','utf8');
const start=source.indexOf('async function nativeMusic()');const end=source.indexOf('\nfunction useIframe',start);
let thenCalls=0;const calls=[];
const plugin=new Proxy({}, {get:(_,name)=>(...args)=>{
 if(name==='then'){thenCalls++;return new Promise(()=>{})}
 calls.push({name,args});return Promise.resolve({ok:true,nativeBuild:'09-11c'});
}});
const context=vm.createContext({moduleResult:{music:plugin}});
vm.runInContext(source.slice(start,end).replace("import('./native.js')","Promise.resolve(moduleResult)"),context);
(async()=>{
 // Reproduce old bug: async returns a Proxy and invokes the phantom `then` method.
 let settled=false;(async()=>plugin)().then(()=>settled=true);
 await new Promise(r=>setImmediate(r));assert.equal(thenCalls,1);assert.equal(settled,false);
 thenCalls=0;
 const music=await Promise.race([context.nativeMusic(),new Promise((_,reject)=>setTimeout(()=>reject(new Error('bridge hung')),500))]);
 assert.equal(thenCalls,0);assert.equal(music.then,undefined);
 for(const name of ['diagnostics','playVideo','status','stop','pause','resume','seek'])assert.equal((await music[name]({videoId:'abcdef12345'})).ok,true);
 assert.equal(calls.length,7);assert.equal(thenCalls,0);
 console.log('PASS: old async Proxy hangs; corrected wrapper resolves and dispatches all 7 native methods without plugin.then().');
})().catch(e=>{console.error(e);process.exitCode=1});
