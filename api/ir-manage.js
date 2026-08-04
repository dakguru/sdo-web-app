// api/ir-manage.js — Vercel serverless function
// Edits (rename / re-classify / replace) or deletes an existing IR/VR report in the
// GitHub repo and keeps Leave Orders/ir-library/manifest.json in sync. Companion to
// api/ir-upload.js — same server-side GitHub token. The token lives ONLY in the Vercel
// environment (env GITHUB_TOKEN) — never in the client page. Writes are gated by the
// shared secret IR_ADMIN_KEY (x-admin-key header); see requireAdmin below.
//
// Body (JSON):
//   { action: "delete", year, filename }
//   { action: "edit", year, filename, newYear, newFilename, contentBase64? }
//
// Required env: GITHUB_TOKEN  (fine-grained PAT, "Contents: Read and write")
//               IR_ADMIN_KEY  (shared admin secret; without it all writes are refused)
// Optional env: GITHUB_REPO   (default "dakguru/sdo-web-app")
//               GITHUB_BRANCH (default "main")

const crypto = require('crypto');

const API = 'https://api.github.com';
const DIR = 'Leave Orders/ir-library';
const MAX_BYTES = 3 * 1024 * 1024; // 3 MB (Vercel request body limit is ~4.5 MB)

// Admin gate. Mutations (edit/delete) require the shared secret IR_ADMIN_KEY, set in
// the Vercel project environment — only administrators know it. The client sends it in
// the `x-admin-key` header; we compare in constant time (via SHA-256 digests, so the
// length is never leaked). Fail CLOSED: if the key isn't configured, refuse all writes.
// Returns null when authorised, or { status, error } to reject.
function requireAdmin(req) {
  const configured = process.env.IR_ADMIN_KEY;
  if (!configured) return { status: 503, error: 'Admin key not configured — set IR_ADMIN_KEY in the Vercel project environment.' };
  const provided = req.headers['x-admin-key'] || '';
  if (!provided) return { status: 401, error: 'Admin authorization required — enter the admin key to manage reports.' };
  const a = crypto.createHash('sha256').update(String(provided)).digest();
  const b = crypto.createHash('sha256').update(String(configured)).digest();
  if (!crypto.timingSafeEqual(a, b)) return { status: 401, error: 'Invalid admin key.' };
  return null;
}

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

// Mirror of the manifest generator / ir-upload filename parsing. Keep in step.
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
  let date = '', sort = 0;
  const d = base.match(/(\d{1,2})[.\-_](\d{1,2})[.\-_](\d{2,4})/);
  if (d) {
    let dd = +d[1], mo = +d[2], yy = +d[3];
    if (yy < 100) yy += 2000;
    if (mo >= 1 && mo <= 12 && dd >= 1 && dd <= 31) {
      date = String(dd).padStart(2, '0') + '.' + String(mo).padStart(2, '0') + '.' + yy;
      sort = yy * 10000 + mo * 100 + dd;
    }
  }
  return { file, year: String(year), type, office, date, sort, sizeKB: Math.round(bytes / 1024) };
}

// Sanitise a filename: strip any path, Office temp prefix, and unsafe chars.
function cleanName(name) {
  return String(name || '')
    .replace(/[\\/]/g, ' ')
    .replace(/^~\$*/, '')
    .replace(/[<>:"|?*\x00-\x1f]/g, '')
    .replace(/\s{2,}/g, ' ')
    .trim();
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

async function ghDelete(url, headers, body) {
  const r = await fetch(url, { method: 'DELETE', headers: { ...headers, 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  if (!r.ok) {
    const txt = await r.text();
    const err = new Error(`GitHub DELETE ${r.status}: ${txt.slice(0, 300)}`);
    err.status = r.status;
    throw err;
  }
  return r.json();
}

// Fetch a file's raw content (base64) reliably, even above 1 MB, via the blob API.
async function fetchContentB64(repo, headers, branch, sha) {
  const blob = await ghGet(`${API}/repos/${repo}/git/blobs/${sha}`, headers);
  if (!blob || !blob.content) throw new Error('Could not read the existing file content.');
  return blob.content.replace(/\s/g, '');
}

// Load, mutate and commit the manifest, with one retry on a stale-sha (409) conflict.
async function updateManifest(repo, headers, branch, mutate, message) {
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const manUrl = `${API}/repos/${repo}/contents/${encPath(DIR + '/manifest.json')}`;
      const man = await ghGet(`${manUrl}?ref=${encodeURIComponent(branch)}`, headers);
      let data = { generatedAt: '', count: 0, items: [] };
      if (man && man.content) {
        try { data = JSON.parse(Buffer.from(man.content, 'base64').toString('utf8')); } catch { /* rebuild */ }
      }
      if (!Array.isArray(data.items)) data.items = [];
      data.items = mutate(data.items);
      data.items.sort((a, b) => String(b.year).localeCompare(String(a.year)) || (b.sort || 0) - (a.sort || 0) || String(a.office).localeCompare(String(b.office)));
      data.count = data.items.length;
      data.generatedAt = new Date().toISOString().slice(0, 16).replace('T', ' ');
      const newContent = Buffer.from(JSON.stringify(data, null, 2), 'utf8').toString('base64');
      await ghPut(manUrl, headers, { message, content: newContent, branch, ...(man && man.sha ? { sha: man.sha } : {}) });
      return;
    } catch (e) {
      if (e.status === 409 && attempt === 0) continue; // sha moved, retry once
      throw e;
    }
  }
}

module.exports = async (req, res) => {
  if (req.method !== 'POST') { res.status(405).json({ ok: false, error: 'POST only' }); return; }

  const denied = requireAdmin(req);
  if (denied) { res.status(denied.status).json({ ok: false, error: denied.error }); return; }

  const token = process.env.GITHUB_TOKEN;
  const repo = process.env.GITHUB_REPO || 'dakguru/sdo-web-app';
  const branch = process.env.GITHUB_BRANCH || 'main';
  if (!token) { res.status(503).json({ ok: false, error: 'Managing reports is not enabled yet — set the GITHUB_TOKEN environment variable in Vercel.' }); return; }

  let body = req.body;
  if (typeof body === 'string') { try { body = JSON.parse(body); } catch { body = {}; } }
  if (!body || typeof body !== 'object') body = {};

  const action = String(body.action || '').trim().toLowerCase();
  const year = String(body.year || '').trim();
  const filename = cleanName(body.filename);

  if (!/^20\d\d$/.test(year)) { res.status(400).json({ ok: false, error: 'Invalid year.' }); return; }
  if (!filename || !/\.docx?$/i.test(filename)) { res.status(400).json({ ok: false, error: 'Invalid filename.' }); return; }

  const headers = gh(token);
  const oldPath = `${DIR}/${year}/${filename}`;
  const oldUrl = `${API}/repos/${repo}/contents/${encPath(oldPath)}`;

  try {
    // ---------------- DELETE ----------------
    if (action === 'delete') {
      const existing = await ghGet(`${oldUrl}?ref=${encodeURIComponent(branch)}`, headers);
      if (existing && existing.sha) {
        await ghDelete(oldUrl, headers, { message: `IR/VR library: delete ${filename} (${year})`, sha: existing.sha, branch });
      }
      await updateManifest(repo, headers, branch,
        items => items.filter(x => !(x.file === filename && String(x.year) === year)),
        `IR/VR library: de-index ${filename}`);
      res.status(200).json({ ok: true, deleted: { file: filename, year } });
      return;
    }

    // ---------------- EDIT ----------------
    if (action === 'edit') {
      const newYear = String(body.newYear || year).trim();
      let newFilename = cleanName(body.newFilename || filename);
      const contentBase64 = String(body.contentBase64 || '').replace(/\s/g, '');

      if (!/^20\d\d$/.test(newYear)) { res.status(400).json({ ok: false, error: 'Invalid year.' }); return; }
      if (!/\.docx?$/i.test(newFilename)) { res.status(400).json({ ok: false, error: 'Only Word .doc / .docx files are allowed.' }); return; }

      const newPath = `${DIR}/${newYear}/${newFilename}`;
      const moved = newPath !== oldPath;

      // Read the existing file (sha, needed to move/replace/delete).
      const oldMeta = await ghGet(`${oldUrl}?ref=${encodeURIComponent(branch)}`, headers);
      if (!oldMeta || !oldMeta.sha) { res.status(404).json({ ok: false, error: 'The report to edit was not found — it may have already changed.' }); return; }

      const hasNewContent = contentBase64.length > 0;
      if (!moved && !hasNewContent) { res.status(400).json({ ok: false, error: 'Nothing to change — rename the report, change its year, or attach a replacement file.' }); return; }

      // Content to write at the new path: the replacement if given, else the existing bytes.
      const content = hasNewContent ? contentBase64 : await fetchContentB64(repo, headers, branch, oldMeta.sha);

      let bytes;
      try { bytes = Buffer.from(content, 'base64').length; }
      catch { res.status(400).json({ ok: false, error: 'File content is not valid base64.' }); return; }
      if (bytes === 0) { res.status(400).json({ ok: false, error: 'Empty file.' }); return; }
      if (bytes > MAX_BYTES) { res.status(413).json({ ok: false, error: 'File larger than 3 MB.' }); return; }

      const newUrl = `${API}/repos/${repo}/contents/${encPath(newPath)}`;

      // If the destination already exists (in-place update, or an overwrite), we need its sha.
      const destSha = moved
        ? (await ghGet(`${newUrl}?ref=${encodeURIComponent(branch)}`, headers) || {}).sha
        : oldMeta.sha;

      // 1) write the file at the new path
      await ghPut(newUrl, headers, {
        message: `IR/VR library: ${moved ? 'rename to' : 'update'} ${newFilename} (${newYear})`,
        content, branch,
        ...(destSha ? { sha: destSha } : {}),
      });

      // 2) if the path changed, remove the old file
      if (moved) {
        await ghDelete(oldUrl, headers, { message: `IR/VR library: remove old ${filename} (${year})`, sha: oldMeta.sha, branch });
      }

      const item = parseMeta(newFilename, newYear, bytes);

      // 3) re-index: drop the old entry and any pre-existing entry at the new key, add the new one
      await updateManifest(repo, headers, branch,
        items => {
          const kept = items.filter(x =>
            !(x.file === filename && String(x.year) === year) &&
            !(x.file === newFilename && String(x.year) === newYear));
          kept.unshift(item);
          return kept;
        },
        `IR/VR library: re-index ${item.file}`);

      res.status(200).json({ ok: true, item, path: newPath, moved });
      return;
    }

    res.status(400).json({ ok: false, error: 'Unknown action. Use "edit" or "delete".' });
  } catch (err) {
    res.status(502).json({ ok: false, error: err.message || 'GitHub request failed.' });
  }
};
