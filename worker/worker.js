// Yükle Detayla — ücretsiz yapay zekâ sunucusu (Cloudflare Worker + Google Gemini ücretsiz kota)
// Gerekli ayarlar (Worker > Settings > Variables and Secrets):
//   GEMINI_API_KEY (Secret) : Google AI Studio'dan alınan ücretsiz anahtar
//   MODELS         (isteğe bağlı) : virgülle ayrılmış model listesi, sırayla denenir
//   REQUIRE_TOKEN  (isteğe bağlı) : "true" yapılınca yalnızca giriş yapmış üyeler kullanabilir.
//                    Yeni sürüm (giriş anahtarı gönderen) mağazalarda yayınlanınca "true" yapın;
//                    o zamana kadar eski sürümler de çalışsın diye kapalıdır.
//   DAILY_LIMIT    (isteğe bağlı) : üye başına günlük istek sınırı (varsayılan 150)
//   LEGACY_DAILY_LIMIT (isteğe bağlı) : giriş anahtarı göndermeyen eski sürümler için
//                    internet adresi (IP) başına günlük sınır (varsayılan 60)
//
// GÜVENLİK: Uygulama her istekte Firebase giriş anahtarını (ID token) gönderir. Sunucu bu anahtarın
// Google tarafından "yukle-detayla" projesi için imzalandığını doğrular; böylece sunucu yalnızca
// uygulamaya giriş yapmış üyelere hizmet verir ve herkesin kotası ayrı sayılır. (Eskiden yalnızca
// herkese açık depoda yazan sabit bir anahtar soruluyordu; başkaları sunucuyu kullanıp kotayı bitirebilirdi.)

const FIREBASE_PROJECT = 'yukle-detayla';

// Her modelin ayrı günlük ücretsiz kotası var: biri dolunca/bulunamazsa sıradakine geçilir.
const DEFAULT_MODELS = [
  'gemini-3.5-flash-lite',
  'gemini-3.1-flash-lite',
  'gemini-2.5-flash-lite',
  'gemini-3.5-flash',
  'gemini-2.5-flash',
];

// Eski sürümlerin gönderdiği sabit kimlik (gizli değildir; yalnızca geçiş dönemi için).
const APP_KEY = 'pJkL9huy6FeWD-uZoZHRyY0Et-JqNGFx';

const CORS = {
  'access-control-allow-origin': '*',
  'access-control-allow-methods': 'POST, OPTIONS',
  'access-control-allow-headers': 'content-type, x-app-key, authorization',
};
const json = (obj, status = 200) =>
  new Response(JSON.stringify(obj), { status, headers: { 'content-type': 'application/json; charset=utf-8', ...CORS } });

// ---------------- Firebase giriş anahtarı doğrulama ----------------
const JWK_URL = 'https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com';
let jwkCache = { until: 0, keys: {} };

async function googleKeys() {
  if (Date.now() < jwkCache.until) return jwkCache.keys;
  const r = await fetch(JWK_URL);
  if (!r.ok) throw new Error('jwk');
  const m = /max-age=(\d+)/.exec(r.headers.get('cache-control') || '');
  const { keys = [] } = await r.json();
  const out = {};
  for (const k of keys) {
    out[k.kid] = await crypto.subtle.importKey('jwk', k, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['verify']);
  }
  jwkCache = { until: Date.now() + Math.min(Number(m ? m[1] : 3600), 21600) * 1000, keys: out };
  return out;
}

const b64urlBytes = s => Uint8Array.from(atob(s.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((s.length + 3) % 4)), c => c.charCodeAt(0));
const b64urlJson = s => JSON.parse(new TextDecoder().decode(b64urlBytes(s)));

// Geçerliyse üyenin kimliğini (uid), değilse null döndürür.
async function verifyIdToken(token) {
  const parts = String(token || '').split('.');
  if (parts.length !== 3) return null;
  let h, p;
  try { h = b64urlJson(parts[0]); p = b64urlJson(parts[1]); } catch { return null; }
  if (h.alg !== 'RS256' || !h.kid) return null;
  const now = Math.floor(Date.now() / 1000);
  if (p.aud !== FIREBASE_PROJECT || p.iss !== 'https://securetoken.google.com/' + FIREBASE_PROJECT) return null;
  if (!p.sub || typeof p.sub !== 'string' || p.sub.length > 128) return null;
  if (!(p.exp > now) || !(p.iat <= now + 300)) return null;
  let keys = await googleKeys();
  if (!keys[h.kid]) { jwkCache.until = 0; keys = await googleKeys(); }   // Google anahtarları yeniledi
  const key = keys[h.kid];
  if (!key) return null;
  const ok = await crypto.subtle.verify('RSASSA-PKCS1-v1_5', key, b64urlBytes(parts[2]),
    new TextEncoder().encode(parts[0] + '.' + parts[1]));
  return ok ? p.sub : null;
}

// ---------------- Kullanım sınırı ----------------
// Sayaçlar Cloudflare önbelleğinde tutulur (her veri merkezinde ayrı; ücretsiz ve ek ayar gerektirmez).
const mem = new Map();
async function countUp(name, ttlSec) {
  const url = 'https://sayac.yukle-detayla.internal/' + encodeURIComponent(name);
  let n = 0;
  try {
    const c = caches.default;
    const old = await c.match(url);
    n = (old ? Number(await old.text()) || 0 : 0) + 1;
    await c.put(url, new Response(String(n), { headers: { 'cache-control': 'max-age=' + ttlSec } }));
  } catch { /* önbellek yoksa yalnızca bellekteki sayaç */ }
  const m = (mem.get(name) || 0) + 1;
  mem.set(name, m);
  if (mem.size > 5000) mem.clear();
  return Math.max(n, m);
}
const today = () => new Date().toISOString().slice(0, 10);
const minute = () => Math.floor(Date.now() / 60000);

async function callGemini(model, key, parts) {
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`;
  const r = await fetch(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-goog-api-key': key },
    body: JSON.stringify({
      contents: [{ role: 'user', parts }],
      generationConfig: { responseMimeType: 'application/json', maxOutputTokens: 32000, temperature: 0.7 },
    }),
  });
  const j = await r.json().catch(() => ({}));
  return { status: r.status, ok: r.ok, j };
}

export default {
  async fetch(req, env) {
    if (req.method === 'OPTIONS') return new Response(null, { headers: CORS });
    if (req.method !== 'POST') return json({ error: 'Sadece POST' }, 405);
    if (!env.GEMINI_API_KEY) return json({ error: 'Sunucuda GEMINI_API_KEY ayarlı değil' }, 500);

    // Kim istiyor?
    const auth = req.headers.get('authorization') || '';
    let who;
    if (auth.startsWith('Bearer ')) {
      let uid = null;
      try { uid = await verifyIdToken(auth.slice(7).trim()); } catch { return json({ error: 'Giriş doğrulanamadı, biraz sonra tekrar dene.' }, 503); }
      if (!uid) return json({ error: 'Oturumun geçersiz. Çıkış yapıp yeniden giriş yap.' }, 401);
      who = { id: 'u:' + uid, daily: Number(env.DAILY_LIMIT) || 150 };
    } else {
      if (String(env.REQUIRE_TOKEN) === 'true') return json({ error: 'Uygulamayı güncelleyip giriş yapmalısın.' }, 401);
      if (req.headers.get('x-app-key') !== (env.APP_KEY || APP_KEY)) return json({ error: 'Yetkisiz' }, 401);
      const ip = req.headers.get('cf-connecting-ip') || 'bilinmiyor';
      who = { id: 'ip:' + ip, daily: Number(env.LEGACY_DAILY_LIMIT) || 60 };
    }
    // Dakikada en fazla 15 istek (art arda saldırıya karşı), günde DAILY_LIMIT istek.
    if (await countUp(who.id + ':' + minute(), 120) > 15) return json({ error: 'Çok hızlı istek' }, 429);
    if (await countUp(who.id + ':' + today(), 90000) > who.daily)
      return json({ error: 'Bugünkü kullanım sınırına ulaştın. Yarın tekrar deneyebilirsin.' }, 403);

    let body;
    try { body = await req.json(); } catch { return json({ error: 'Geçersiz istek' }, 400); }
    const prompt = String(body.prompt || '');
    const images = Array.isArray(body.images) ? body.images.slice(0, 20).filter(x => typeof x === 'string') : [];
    if (!prompt) return json({ error: 'Boş istek' }, 400);
    if (prompt.length > 600000) return json({ error: 'Belge çok uzun' }, 413);

    const parts = [
      ...images.map(data => ({ inline_data: { mime_type: 'image/jpeg', data } })),
      { text: prompt },
    ];

    const models = (env.MODELS ? env.MODELS.split(',') : DEFAULT_MODELS).map(s => s.trim()).filter(Boolean);
    let lastErr = 'Yapay zekâ yanıt vermedi';
    let limited = false;
    for (const model of models) {
      const { status, ok, j } = await callGemini(model, env.GEMINI_API_KEY, parts);
      if (ok) {
        const cand = (j.candidates || [])[0] || {};
        const text = ((cand.content && cand.content.parts) || []).map(p => p.text || '').join('');
        if (!text) { lastErr = 'Boş yanıt (' + (cand.finishReason || 'bilinmiyor') + ')'; continue; }
        return json({ text, truncated: cand.finishReason === 'MAX_TOKENS', model });
      }
      lastErr = (j.error && j.error.message) || ('Gemini hatası ' + status);
      if (status === 429) { limited = true; continue; }        // bu modelin kotası doldu → sıradaki
      if (status === 404 || status === 400 && /model/i.test(lastErr)) continue; // model yok → sıradaki
      if (status >= 500) continue;                              // geçici hata → sıradaki
      return json({ error: lastErr }, 502);
    }
    return json({ error: lastErr }, limited ? 429 : 502);
  },
};
