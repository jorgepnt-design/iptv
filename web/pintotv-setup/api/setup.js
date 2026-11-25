import { put, list, del } from '@vercel/blob';
import crypto from 'node:crypto';

const CODE = /^PT-[A-Z2-9]{4}-[A-Z2-9]{4}$/;
const prefix = (code) =>
  's/' + crypto.createHash('sha256').update((process.env.SETUP_SALT || '') + code).digest('hex') + '/';

async function items(code) {
  const { blobs } = await list({ prefix: prefix(code), limit: 100 });
  return blobs.sort((a, b) => a.pathname.localeCompare(b.pathname));
}

export default async function handler(req, res) {
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Headers', 'content-type');
  res.setHeader('Access-Control-Allow-Methods', 'GET,POST,DELETE,OPTIONS');
  if (req.method === 'OPTIONS') return res.status(204).end();
  try {
    const code = String((req.method === 'POST' ? req.body?.code : req.query.code) || '').toUpperCase().trim();
    if (!CODE.test(code)) return res.status(400).json({ error: 'Ungültiger Gerätecode' });

    if (req.method === 'GET') {
      const out = [];
      for (const b of await items(code)) {
        const r = await fetch(b.url, { cache: 'no-store' });
        if (!r.ok) continue;
        const d = await r.json();
        out.push({ id: b.pathname.split('/').pop().replace('.json', ''), kind: d.kind, payload: d.payload });
      }
      return res.status(200).json(out);
    }

    if (req.method === 'POST') {
      const { kind, payload } = req.body || {};
      if (kind !== 'playlist' && kind !== 'backup') return res.status(400).json({ error: 'Unbekannte Art' });
      if (!payload || typeof payload !== 'object') return res.status(400).json({ error: 'Keine Daten' });
      if (kind === 'playlist') {
        const p = payload;
        const ok = p.type === 'm3u' ? !!p.url : !!(p.server && p.user);
        if (!ok) return res.status(400).json({ error: 'Bitte alle Pflichtfelder ausfüllen' });
      } else if (!payload.prefs) return res.status(400).json({ error: 'Das ist keine PintoTV-Sicherung' });
      const existing = await items(code);
      if (existing.length >= 20) return res.status(429).json({ error: 'Zu viele offene Einträge für dieses Gerät' });
      // eine komplette Einrichtung ersetzt alles, was vorher für dieses Gerät wartete
      if (kind === 'backup' && existing.length) await del(existing.map((b) => b.url));
      const id = Date.now().toString().padStart(15, '0') + crypto.randomBytes(3).toString('hex');
      await put(prefix(code) + id + '.json', JSON.stringify({ kind, payload }), {
        access: 'public', addRandomSuffix: true, contentType: 'application/json', cacheControlMaxAge: 60,
      });
      return res.status(200).json({ ok: true });
    }

    if (req.method === 'DELETE') {
      const upto = String(req.query.upto || '');
      const old = (await items(code)).filter((b) => !upto || b.pathname.split('/').pop() <= upto + '￿');
      if (old.length) await del(old.map((b) => b.url));
      return res.status(200).json({ ok: true, deleted: old.length });
    }
    res.status(405).json({ error: 'Nicht erlaubt' });
  } catch (e) {
    res.status(500).json({ error: String(e && e.message || e) });
  }
}
