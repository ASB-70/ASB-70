// ملصق الأسعار — app shell cache so the app opens fast and works on a weak connection.
// The page itself is fetched fresh when online (so updates arrive), and served from cache when offline.
const CACHE = 'price-label-v3';
const SHELL = ['./', './index.html', './manifest.webmanifest', './icons/icon-192.png', './icons/icon-512.png'];
self.addEventListener('install', e=>{
  e.waitUntil(caches.open(CACHE).then(c=>c.addAll(SHELL)).then(()=>self.skipWaiting()));
});
self.addEventListener('activate', e=>{
  e.waitUntil(caches.keys().then(ks=>Promise.all(ks.filter(k=>k!==CACHE).map(k=>caches.delete(k)))).then(()=>self.clients.claim()));
});
self.addEventListener('fetch', e=>{
  const req = e.request;
  if(req.method !== 'GET') return;
  const url = new URL(req.url);
  if(url.hostname.endsWith('supabase.co')) return;              // live data: never cache
  if(url.pathname.includes('/__shared__/')) return;            // images shared in from the Android app
  if(req.mode === 'navigate' || (url.origin === location.origin && url.pathname.endsWith('/index.html'))){
    e.respondWith(fetch(req).then(r=>{ const cp = r.clone(); caches.open(CACHE).then(c=>c.put('./index.html', cp)); return r; })
      .catch(()=>caches.match('./index.html').then(r=>r || caches.match('./'))));
    return;
  }
  const cdn = /(cdnjs\.cloudflare\.com|fonts\.googleapis\.com|fonts\.gstatic\.com)$/.test(url.hostname);
  if(cdn || url.origin === location.origin){
    e.respondWith(caches.match(req).then(hit=> hit || fetch(req).then(r=>{
      if(r && (r.ok || r.type==='opaque')){ const cp = r.clone(); caches.open(CACHE).then(c=>c.put(req, cp)); }
      return r;
    })));
  }
});
