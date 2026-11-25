// Lädt die Gruppen/Kategorien einer Playlist, damit man auf der Seite genau auswählen kann
const UA = 'IPTVSmartersPlayer';

function base(server) {
  let s = String(server || '').trim();
  if (!/^https?:\/\//i.test(s)) s = 'http://' + s;
  try { const u = new URL(s); return u.protocol + '//' + u.host; } catch { return null; }
}

async function get(url, ms = 20000) {
  const ctl = new AbortController();
  const t = setTimeout(() => ctl.abort(), ms);
  try {
    const r = await fetch(url, { headers: { 'User-Agent': UA }, signal: ctl.signal, redirect: 'follow' });
    if (!r.ok) throw new Error('Anbieter antwortet mit Fehler ' + r.status);
    return await r.text();
  } finally { clearTimeout(t); }
}

const names = (txt) => {
  const a = JSON.parse(txt);
  return Array.isArray(a) ? [...new Set(a.map((o) => String(o.category_name || '').trim()).filter(Boolean))] : [];
};

export default async function handler(req, res) {
  res.setHeader('Cache-Control', 'no-store');
  if (req.method !== 'POST') return res.status(405).json({ error: 'Nicht erlaubt' });
  const p = req.body || {};
  try {
    if (p.type === 'xtream') {
      const b = base(p.server);
      if (!b || !p.user) return res.status(400).json({ error: 'Server und Benutzername fehlen' });
      const a = `${b}/player_api.php?username=${encodeURIComponent(p.user)}&password=${encodeURIComponent(p.pass || '')}`;
      const info = JSON.parse(await get(a));
      if (info && info.user_info && String(info.user_info.auth) === '0') return res.status(401).json({ error: 'Zugangsdaten falsch' });
      const [live, movies, series] = await Promise.all([
        get(a + '&action=get_live_categories').then(names).catch(() => []),
        get(a + '&action=get_vod_categories').then(names).catch(() => []),
        get(a + '&action=get_series_categories').then(names).catch(() => []),
      ]);
      return res.status(200).json({ live, movies, series });
    }
    if (p.type === 'm3u') {
      if (!p.url) return res.status(400).json({ error: 'Playlist-Adresse fehlt' });
      const txt = await get(p.url, 45000);
      const live = new Set(), movies = new Set(), series = new Set();
      const lines = txt.split(/\r?\n/);
      let g = null;
      for (const l of lines) {
        if (l.startsWith('#EXTINF')) { const m = l.match(/group-title="([^"]*)"/i); g = m ? m[1].trim() : ''; continue; }
        if (g === null || !l || l.startsWith('#')) continue;
        if (g) (/\/series\//i.test(l) ? series : /\/movie\//i.test(l) ? movies : live).add(g);
        g = null;
      }
      return res.status(200).json({ live: [...live], movies: [...movies], series: [...series] });
    }
    res.status(400).json({ error: 'Unbekannte Art' });
  } catch (e) {
    const c = e.cause && (e.cause.code || '');
    if (e.name === 'AbortError' || /TIMEOUT|ECONNREFUSED|ECONNRESET|EHOSTUNREACH/.test(c))
      return res.status(502).json({ error: 'Dein Anbieter lässt Abfragen von Servern nicht zu – hier ist das Laden der Gruppen deshalb nicht möglich. Lösung: die genaue Auswahl aus der PintoTV-App senden (Playlists → ⇄ Übertragen → Meine Einrichtung an Gerätecode senden → Genau auswählen) oder unten nur die Länder antippen.' });
    res.status(502).json({ error: e.name === 'AbortError' ? 'Der Anbieter antwortet nicht (Zeitüberschreitung)' : 'Gruppen konnten nicht geladen werden: ' + (e.message || e) + (e.cause ? ' (' + (e.cause.code || e.cause.message || e.cause) + ')' : '') });
  }
}
