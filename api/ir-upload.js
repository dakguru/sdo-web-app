// api/ir-upload.js — Vercel serverless function
// Commits an uploaded IR/VR report to the GitHub repo as a real file (not a DB row)
// and keeps Leave Orders/ir-library/manifest.json in sync. The GitHub token lives
// ONLY in the Vercel environment (env GITHUB_TOKEN) — never in the client page.
//
// Required env: GITHUB_TOKEN  (a fine-grained PAT with "Contents: Read and write"
//                              on the repo below)
// Optional env: GITHUB_REPO   (default "dakguru/sdo-web-app")
//               GITHUB_BRANCH (default "main")

const API = 'https://api.github.com';
const DIR = 'Leave Orders/ir-library';
const MAX_BYTES = 3 * 1024 * 1024; // 3 MB (Vercel request body limit is ~4.5 MB)

function gh(token) {
  return {
    Authorization: `Bearer ${token}`,
    Accept: 'application/vnd.github+json',
    'X-GitHub-Api-Version': '2022-11-28',
    'User-Agent': 'krrsdo-ir-library',
  };
}

// Encode each path segment but keep the slashes.
function encPath(p) {
  return p.split('/').map(encodeURIComponent).join('/');
}

// Mirror of the manifest generator's filename parsing (keep in step with the PS script).
function parseMeta(file, year, bytes) {
  const base = file.replace(/\.[^.]+$/, '');
  const type = /\bVR\b/i.test(base) ? 'VR' : 'IR';
  let office = base;
  const m = base.match(/^(.*?)\s*[-–]\s/);
  if (m) office = m[1];
  office = office
    .replace(/^(IR|VR)\s+of\s+/i, '')
    .replace(/\bFINAL\s+REPORT\b/i, '')
    .replace(/\s*[-–]\s*(IR|VR)\b.*$/i, '')
    .replace(/\s{2,}/g, ' ')
    .trim() || base;
  let date = '';
  const d = base.match(/(\d{1,2})[.\-_](\d{1,2})[.\-_](\d{2,4})/);
  if (d) {
    let dd = +d[1], mo = +d[2], yy = +d[3];
    if (yy < 100) yy += 2000;
    if (mo >= 1 && mo <= 12 && dd >= 1 && dd <= 31) {
      date = String(dd).padStart(2, '0') + '.' + String(mo).padStart(2, '0') + '.' + yy;
    }
  }
  return { file, year: String(year), type, office, date, sizeKB: Math.round(bytes / 1024) };
}

async function ghGet(url, headers) {
  const r = await fetch(url, { headers });
  if (r.status === 404) return null;
  if (!r.ok) throw new Error(`GitHub GET ${r.status}: ${(await r.text()).slice(0, 300)}`);
  return r.json();
}

async function ghPut(url, headers, body) {
  const r = await fetch(url, { method: 'PUT', headers: { ...headers, 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  if (!r.ok) {
    const txt = await r.text();
    const err = new Error(`GitHub PUT ${r.status}: ${txt.slice(0, 300)}`);
    err.status = r.status;
    throw err;
  }
  return r.json();
}

module.exports = async (req, res) => {
  if (req.method !== 'POST') { res.status(405).json({ ok: false, error: 'POST only' }); return; }

  const token = process.env.GITHUB_TOKEN;
  const repo = process.env.GITHUB_REPO || 'dakguru/sdo-web-app';
  const branch = process.env.GITHUB_BRANCH || 'main';
  if (!token) { res.status(503).json({ ok: false, error: 'Uploads are not enabled yet — set the GITHUB_TOKEN environment variable in Vercel.' }); return; }

  let body = req.body;
  if (typeof body === 'string') { try { body = JSON.parse(body); } catch { body = {}; } }
  if (!body || typeof body !== 'object') body = {};

  const year = String(body.year || '').trim();
  let filename = String(body.filename || '').trim();
  const contentBase64 = String(body.contentBase64 || '');

  if (!/^20\d\d$/.test(year)) { res.status(400).json({ ok: false, error: 'Invalid year.' }); return; }
  if (!/\.docx?$/i.test(filename)) { res.status(400).json({ ok: false, error: 'Only Word .doc / .docx files are allowed.' }); return; }
  if (!contentBase64) { res.status(400).json({ ok: false, error: 'No file content received.' }); return; }

  // sanitise filename: strip any path, Office temp prefix, and unsafe chars
  filename = filename.replace(/[\\/]/g, ' ').replace(/^~\$*/, '').replace(/[<>:"|?*\x00-\x1f]/g, '').replace(/\s{2,}/g, ' ').trim();
  if (!filename) { res.status(400).json({ ok: false, error: 'Invalid filename.' }); return; }

  let bytes;
  try { bytes = Buffer.from(contentBase64, 'base64').length; }
  catch { res.status(400).json({ ok: false, error: 'File content is not valid base64.' }); return; }
  if (bytes === 0) { res.status(400).json({ ok: false, error: 'Empty file.' }); return; }
  if (bytes > MAX_BYTES) { res.status(413).json({ ok: false, error: 'File larger than 3 MB.' }); return; }

  const headers = gh(token);
  const filePath = `${DIR}/${year}/${filename}`;
  const fileUrl = `${API}/repos/${repo}/contents/${encPath(filePath)}`;

  try {
    // 1) commit the report file (update in place if it already exists)
    const existing = await ghGet(`${fileUrl}?ref=${encodeURIComponent(branch)}`, headers);
    await ghPut(fileUrl, headers, {
      message: `IR/VR library: ${existing ? 'update' : 'add'} ${filename} (${year})`,
      content: contentBase64,
      branch,
      ...(existing && existing.sha ? { sha: existing.sha } : {}),
    });

    const item = parseMeta(filename, year, bytes);

    // 2) upsert the manifest (one retry on a stale-sha conflict)
    for (let attempt = 0; attempt < 2; attempt++) {
      try {
        const manUrl = `${API}/repos/${repo}/contents/${encPath(DIR + '/manifest.json')}`;
        const man = await ghGet(`${manUrl}?ref=${encodeURIComponent(branch)}`, headers);
        let data = { generatedAt: '', count: 0, items: [] };
        if (man && man.content) {
          try { data = JSON.parse(Buffer.from(man.content, 'base64').toString('utf8')); } catch { /* rebuild */ }
        }
        if (!Array.isArray(data.items)) data.items = [];
        data.items = data.items.filter(x => !(x.file === item.file && String(x.year) === item.year));
        data.items.unshift(item);
        data.items.sort((a, b) => String(b.year).localeCompare(String(a.year)) || (b.sort || 0) - (a.sort || 0) || String(a.office).localeCompare(String(b.office)));
        data.count = data.items.length;
        data.generatedAt = new Date().toISOString().slice(0, 16).replace('T', ' ');
        const newContent = Buffer.from(JSON.stringify(data, null, 2), 'utf8').toString('base64');
        await ghPut(manUrl, headers, {
          message: `IR/VR library: index ${item.file}`,
          content: newContent,
          branch,
          ...(man && man.sha ? { sha: man.sha } : {}),
        });
        break;
      } catch (e) {
        if (e.status === 409 && attempt === 0) continue; // sha moved, retry once
        throw e;
      }
    }

    res.status(200).json({ ok: true, item, path: filePath });
  } catch (err) {
    res.status(502).json({ ok: false, error: err.message || 'GitHub commit failed.' });
  }
};
