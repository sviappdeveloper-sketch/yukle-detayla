// PDF Çalışma — ücretsiz yapay zekâ sunucusu (Cloudflare Worker + Google Gemini ücretsiz kota)
// Gerekli ayarlar (Worker > Settings > Variables and Secrets):
//   GEMINI_API_KEY (Secret) : Google AI Studio'dan alınan ücretsiz anahtar
//   APP_KEY        (Secret) : uygulamadaki config.js içindeki appKey ile aynı
//   MODELS         (isteğe bağlı) : virgülle ayrılmış model listesi, sırayla denenir

// Her modelin ayrı günlük ücretsiz kotası var: biri dolunca/bulunamazsa sıradakine geçilir.
const DEFAULT_MODELS = [
  'gemini-3.5-flash-lite',
  'gemini-3.1-flash-lite',
  'gemini-2.5-flash-lite',
  'gemini-3.5-flash',
  'gemini-2.5-flash',
];

const CORS = {
  'access-control-allow-origin': '*',
  'access-control-allow-methods': 'POST, OPTIONS',
  'access-control-allow-headers': 'content-type, x-app-key',
};
const json = (obj, status = 200) =>
  new Response(JSON.stringify(obj), { status, headers: { 'content-type': 'application/json; charset=utf-8', ...CORS } });

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
    if (!env.APP_KEY || req.headers.get('x-app-key') !== env.APP_KEY) return json({ error: 'Yetkisiz' }, 401);
    if (!env.GEMINI_API_KEY) return json({ error: 'Sunucuda GEMINI_API_KEY ayarlı değil' }, 500);

    let body;
    try { body = await req.json(); } catch { return json({ error: 'Geçersiz istek' }, 400); }
    const prompt = String(body.prompt || '');
    const images = Array.isArray(body.images) ? body.images.slice(0, 20) : [];
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
