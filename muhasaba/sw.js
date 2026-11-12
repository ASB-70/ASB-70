// محاسبة شركة الثقة: يفتح البرنامج حتى لو انقطع الإنترنت (البيانات نفسها تحتاج اتصالاً)
const C='muhasaba-v2';
self.addEventListener('install',e=>{self.skipWaiting();e.waitUntil(caches.open(C).then(c=>c.addAll(['./','manifest.webmanifest','icon-192.png'])).catch(()=>{}))});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(k=>Promise.all(k.filter(x=>x!==C).map(x=>caches.delete(x)))).then(()=>self.clients.claim()))});
self.addEventListener('fetch',e=>{const r=e.request,u=new URL(r.url);if(r.method!=='GET'||u.origin!==location.origin)return;
  // always ask the server for the page itself so updates arrive at once; fall back to the saved copy when offline
  const fresh=r.mode==='navigate'||u.pathname.endsWith('version.txt');
  e.respondWith(fetch(fresh?new Request(r.url,{cache:'no-store'}):r).then(res=>{if(res.ok&&!u.pathname.endsWith('version.txt')){const cp=res.clone();caches.open(C).then(c=>c.put(fresh?u.origin+u.pathname:r,cp))}return res}).catch(()=>caches.match(r,{ignoreSearch:true}).then(m=>m||caches.match('./'))))});
