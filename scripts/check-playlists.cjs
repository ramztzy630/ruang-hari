'use strict';
const fs=require('node:fs'),vm=require('node:vm');
const js=fs.readFileSync('dist/app.js','utf8');
const block=js.slice(js.indexOf('/* ===== PLAYLIST START'),js.indexOf('/* ===== PLAYLIST END'));
// stub environment
const store={};const toasts=[];const clicks=[];
const mkEl=()=>({value:'',textContent:'',innerHTML:'',open:false,hidden:false,dataset:{},addEventListener(){},showModal(){this.open=true},close(){this.open=false},focus(){},outerHTML:''});
const els={};const $=s=>els[s]||(els[s]=mkEl());
const ctx={console,setTimeout,Date,Math,Number,String,Array,Set,JSON,Error,
  esc:s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])),
  ic:n=>`[${n}]`,hdArtwork:u=>u?u.replace('w60','w600'):'',
  read:(k,f)=>{try{return JSON.parse(store[k])??f}catch{return f}},
  write:(k,d)=>{store[k]=JSON.stringify(d);return true},
  uid:(()=>{let i=0;return()=>'pl-'+(++i)})(),toast:m=>toasts.push(m),$,confirm:()=>true,
  actions:{},document:{createElement:()=>({dataset:{},click(){clicks.push({...this.dataset})},remove(){}}),body:{append(){}},addEventListener(){}},
  onlineCurrent:null,onlineSongs:new Map()};
// createElement click must record dataset
ctx.document.createElement=()=>{const b={dataset:{},hidden:false,click(){clicks.push({...b.dataset})},remove(){}};return b};
vm.createContext(ctx);
vm.runInContext("const PL_KEY='rh.playlists',PL_MAX=60,PL_SONG_MAX=500;let playlists={items:[]},playQueue=null,pickerSong=null,openPlaylistId=null,nameMode=null;",ctx);
vm.runInContext(block,ctx);
const run=c=>vm.runInContext(c,ctx);
const assert=(c,m)=>{if(!c){console.error('FAIL:',m);process.exitCode=1}else console.log('ok  ',m)};
const S=(id,t,extra={})=>({id,title:t,artist:'A',artistName:'A',album:'Al',cover:'https://lh3.googleusercontent.com/x=w60-h60',...extra});

// create + add
const p=run(`createPlaylist('  Santai sore  ',${JSON.stringify(S('aaaaaaaaaaa','Satu'))})`);
assert(p.name==='Santai sore'&&p.songs.length===1,'createPlaylist trim + lagu pertama');
assert(JSON.parse(store['rh.playlists']).items.length===1,'tersimpan di localStorage (rh.playlists)');
assert(run(`addSongToPlaylist('${p.id}',${JSON.stringify(S('bbbbbbbbbbb','Dua'))})`)===true,'tambah lagu baru');
assert(run(`addSongToPlaylist('${p.id}',${JSON.stringify(S('bbbbbbbbbbb','Dua'))})`)===false,'duplikat ditolak');
let threw=false;try{run(`createPlaylist('   ')`)}catch{threw=true}assert(threw,'nama kosong ditolak');
assert(run(`cleanSong({id:'bad id',title:'x'})`)===null,'id tidak valid ditolak');
assert(run(`cleanSong({id:'ccccccccccc',title:'x',cover:'http://evil/x.jpg'})`).cover==='','cover non-https dibuang');
// rename / remove
run(`renamePlaylist('${p.id}','Baru')`);assert(run(`findPlaylist('${p.id}').name`)==='Baru','rename');
run(`removeFromPlaylist('${p.id}','aaaaaaaaaaa')`);assert(run(`findPlaylist('${p.id}').songs.length`)===1,'hapus lagu dari playlist');
// reload dari storage
run(`playlists=loadPlaylists()`);assert(run(`playlists.items[0].songs[0].id`)==='bbbbbbbbbbb','muat ulang dari localStorage');
// data rusak
store['rh.playlists']='{"items":[{"id":"x","name":"ok","songs":[{"id":"zz"},null,5]},7,null]}';
run(`playlists=loadPlaylists()`);assert(run(`playlists.items.length`)===1&&run(`playlists.items[0].songs.length`)===0,'data rusak disaring');
store['rh.playlists']='bukan json';run(`playlists=loadPlaylists()`);assert(run(`playlists.items.length`)===0,'JSON rusak -> kosong, tidak crash');
// merge backup
run(`playlists={items:[]}`);const a=run(`createPlaylist('A',${JSON.stringify(S('aaaaaaaaaaa','Satu'))})`);
const imp=[{id:a.id,name:'A',songs:[S('aaaaaaaaaaa','Satu'),S('ddddddddddd','Empat')]},{id:'new-1',name:'Baru',songs:[S('eeeeeeeeeee','Lima')]}];
assert(run(`mergePlaylists(${JSON.stringify(imp)})`)===2,'merge: 1 lagu baru + 1 playlist baru');
assert(run(`playlists.items.length`)===2&&run(`findPlaylist('${a.id}').songs.length`)===2,'merge tidak menduplikasi');
// markup
const html=run(`playlistsPanel()`);assert(html.includes('data-open-playlist')&&html.includes('Playlist saya'),'panel markup');
run(`playlists.items[0].name='<img onerror=x>'`);assert(!run(`playlistsPanel()`).includes('<img onerror'),'nama di-escape (XSS)');
// queue
const q=['a1a1a1a1a1a','b2b2b2b2b2b','c3c3c3c3c3c','d4d4d4d4d4d'].map((id,i)=>S(id,'T'+i));
run(`playQueue=${JSON.stringify(q)};onlineCurrent={id:'c3c3c3c3c3c'}`);
assert(run(`buildNativeQueue('c3c3c3c3c3c').map(s=>s.id).join()`)==='c3c3c3c3c3c,d4d4d4d4d4d,a1a1a1a1a1a,b2b2b2b2b2b','antrean dirotasi mulai dari lagu aktif');
run(`adjacentOnline(1)`);assert(clicks.at(-1).onlineSong==='d4d4d4d4d4d'&&clicks.at(-1).keepQueue==='1','next -> lagu berikutnya, antrean dipertahankan');
run(`onlineCurrent={id:'a1a1a1a1a1a'}`);run(`adjacentOnline(-1)`);assert(clicks.at(-1).onlineSong==='d4d4d4d4d4d','previous dari lagu pertama -> wrap ke terakhir');
// playPlaylist
const pp=run(`createPlaylist('P',${JSON.stringify(S('f6f6f6f6f6f','Enam'))})`);run(`addSongToPlaylist('${pp.id}',${JSON.stringify(S('g7g7g7g7g7g','Tujuh'))})`);
run(`playPlaylist('${pp.id}',{startId:'g7g7g7g7g7g'})`);
assert(clicks.at(-1).onlineSong==='g7g7g7g7g7g'&&run(`playQueue.length`)===2,'putar dari lagu tertentu + antrean = playlist');
assert(run(`playQueue[0].cover`).includes('w600'),'cover HD dipakai saat diputar');
run(`playPlaylist('${pp.id}',{shuffle:true})`);assert(run(`playQueue.length`)===2,'acak tetap membawa semua lagu');
// batas
run(`playlists={items:[]}`);for(let i=0;i<60;i++)run(`createPlaylist('x${i}')`);threw=false;try{run(`createPlaylist('lebih')`)}catch{threw=true}assert(threw,'batas 60 playlist');
if(process.exitCode)throw new Error('check-playlists gagal');
console.log('PASS: playlist CRUD, localStorage, merge cadangan, antrean, escaping');
