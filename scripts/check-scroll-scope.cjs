const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const js=fs.readFileSync('dist/app.js','utf8'),css=fs.readFileSync('dist/style.css','utf8');
assert(!js.includes("classList.toggle('fit-player'"));assert(!css.includes('body.fit-player'));
const classes=new Set(),events={},dialog={open:false,setAttribute(){},addEventListener(n,f){events[n]=f},showModal(){this.open=true},close(){this.open=false;events.close()}};
let restored=0;const c=vm.createContext({document:{createElement:()=>dialog,body:{append(){},classList:{add:n=>classes.add(n),remove:n=>classes.delete(n)}}},onlineCurrent:{id:'test'},onlineMusicCard:()=>'<button data-action="np-stop" aria-label="Hentikan lagu">×</button>',actions:{},syncSubtitle(){},updateOnlineCard(){},positionMiniPlayer(){restored++}});
vm.runInContext(js.slice(js.indexOf("const fitNowPlaying=document.createElement"),js.indexOf("function hdArtwork(")),c);
c.actions['open-fit-player']();assert(dialog.open);assert(classes.has('player-modal-open'));assert(dialog.innerHTML.includes('close-fit-player'));
c.actions['close-fit-player']();assert(!dialog.open);assert(!classes.has('player-modal-open'));assert.equal(restored,1);
console.log('PASS: no global music-page scroll lock; fullscreen opens/closes and restores mini-player');
