const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const app=fs.readFileSync('dist/app.js','utf8');
const helpers=app.slice(app.indexOf('function parseLrc('),app.indexOf("actions['retry-lyrics']"));
const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const c=vm.createContext({console,esc,ic:()=>'<svg></svg>',clock:ms=>String(ms||0),document:{querySelectorAll:()=>[]},onlineCurrent:{id:'abcdefghijk',title:'<script>alert(1)</script>',artistName:'Artist',album:'Album',cover:'https://example.com/cover.jpg'},onlineState:{playing:true,positionMs:1500,durationMs:120000},onlineLyrics:{state:'ready',lines:[],plain:'<img src=x onerror=alert(1)>'},lyricsSequence:0,lyricsTrackKey:'',setTimeout,clearTimeout});
vm.runInContext(helpers,c);
const lines=c.parseLrc('[offset:100]\n[00:02.5][00:03.050]Second\n[00:01.00]First\n[ar:Artist]');
assert.equal(lines.length,3);assert.equal(lines[0].ms,1100);assert.equal(lines[1].ms,2600);assert.equal(lines[2].ms,3150);
assert.equal(c.activeLyric(lines,1000),-1);assert.equal(c.activeLyric(lines,1100),0);assert.equal(c.activeLyric(lines,99999),2);
const html=c.onlineMusicCard();assert(html.includes('online-card-seek'));assert(html.includes('https://example.com/cover.jpg'));assert(html.includes('Album'));assert(!html.includes('<script>alert'));
assert(!c.lyricsMarkup().includes('<img src=x'));
c.onlineCurrent.cover='javascript:alert(1)';assert(!c.onlineMusicCard().includes('javascript:'));
(async()=>{
 let resolveOld;c.nativeMusic=async()=>({lyrics:()=>new Promise(r=>resolveOld=r)});
 const old=c.loadSongLyrics(c.onlineCurrent,120);await new Promise(r=>setImmediate(r));
 c.onlineCurrent={id:'different01',title:'Other'};c.lyricsSequence++;resolveOld({plain:'Stale lyrics'});await old;
 assert.notEqual(c.onlineLyrics.plain,'Stale lyrics');
 console.log('PASS: LRC timing, line selection, metadata/artwork, escaping, stale request protection');
})().catch(e=>{console.error(e);process.exitCode=1});
