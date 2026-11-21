// Variables used by Scriptable.
// These must be at the very top of the file. Do not edit.
// icon-color: pink; icon-glyph: heart;

/*
 * UsTwo for iPhone — runs inside the free "Scriptable" app.
 *  • As a home-screen widget: shows the latest photo either of you sent.
 *  • Tap the widget → PIN → full-screen photo, send a photo, memories.
 *  • Shortcuts / share sheet: send a photo in one tap (see SHORTCUTS.md).
 * Uses the same GitHub repo layout as the Android app (feed.json + photos/).
 */

// The shared "database" repo (pre-filled on first setup, still editable).
const DEFAULT_REPO = "sridhanalakshmimetalstup-svg/movimentwithus";

const KEY = "ustwo.config.v1";
const LOCK_KEY = "ustwo.lock.v1";
const FEED = "feed.json";
const MAX_FEED = 200;
const MAX_UPLOAD = 1600;

const fm = FileManager.local();
const dir = fm.joinPath(fm.documentsDirectory(), "ustwo");
if (!fm.fileExists(dir)) fm.createDirectory(dir, true);
const LATEST_IMG = fm.joinPath(dir, "latest.jpg");
const LATEST_META = fm.joinPath(dir, "latest.json");
const scriptUrl = (action) =>
  `scriptable:///run/${encodeURIComponent(Script.name())}?action=${action}`;

// ------------------------------------------------------------------ config

function loadConfig() {
  if (!Keychain.contains(KEY)) return null;
  try { return JSON.parse(Keychain.get(KEY)); } catch (e) { return null; }
}
function saveConfig(c) { Keychain.set(KEY, JSON.stringify(c)); }

function parseRepo(input) {
  const cleaned = (input || "").trim()
    .replace(/^https?:\/\//, "").replace(/^github\.com\//, "")
    .replace(/\.git$/, "").replace(/\/$/, "");
  const parts = cleaned.split("/").filter(Boolean);
  return parts.length === 2 ? { owner: parts[0], repo: parts[1] } : null;
}

// ------------------------------------------------------------------ GitHub

const base = (c) => `https://api.github.com/repos/${c.owner}/${c.repo}`;
const headers = (c, accept) => ({
  Authorization: `Bearer ${c.token}`,
  Accept: accept || "application/vnd.github+json",
  "X-GitHub-Api-Version": "2022-11-28",
  "User-Agent": "UsTwo-iOS",
});

function ghError(code, body) {
  const msgs = {
    401: "GitHub rejected the token (expired or wrong). Run setup again.",
    403: "The token needs Contents: Read and write on this repo.",
    404: "Repo not found, or the token wasn't given access to it.",
    409: "You both posted at the same moment — try again.",
    422: "You both posted at the same moment — try again.",
  };
  const e = new Error(msgs[code] || `GitHub ${code}: ${(body || "").slice(0, 150)}`);
  e.status = code;
  return e;
}

async function checkAccess(c) {
  const req = new Request(base(c));
  req.headers = headers(c);
  const body = await req.loadString();
  const code = req.response.statusCode;
  if (code !== 200) throw ghError(code, body);
  if (!JSON.parse(body).private) {
    throw new Error("That repo is PUBLIC — anyone could see your photos. Make it private first.");
  }
}

async function readFeed(c) {
  const req = new Request(`${base(c)}/contents/${FEED}?t=${Date.now()}`);
  req.headers = headers(c);
  const body = await req.loadString();
  const code = req.response.statusCode;
  if (code === 404) return { feed: [], sha: null };
  if (code < 200 || code > 299) throw ghError(code, body);
  const obj = JSON.parse(body);
  const text = Data.fromBase64String(obj.content.replace(/\s/g, "")).toRawString();
  return { feed: JSON.parse(text), sha: obj.sha };
}

async function download(c, path) {
  const req = new Request(`${base(c)}/contents/${path}`);
  req.headers = headers(c, "application/vnd.github.raw");
  const data = await req.load();
  const code = req.response.statusCode;
  if (code !== 200) throw ghError(code, "");
  return data;
}

async function put(c, path, payload) {
  const req = new Request(`${base(c)}/contents/${path}`);
  req.method = "PUT";
  req.headers = { ...headers(c), "Content-Type": "application/json" };
  req.body = JSON.stringify(payload);
  const body = await req.loadString();
  const code = req.response.statusCode;
  if (code < 200 || code > 299) throw ghError(code, body);
}

/** Redraw = bake in rotation + shrink to maxSide. */
function resize(img, maxSide) {
  const { width, height } = img.size;
  const scale = Math.min(1, maxSide / Math.max(width, height));
  const w = Math.max(1, Math.round(width * scale));
  const h = Math.max(1, Math.round(height * scale));
  const ctx = new DrawContext();
  ctx.size = new Size(w, h);
  ctx.opaque = true;
  ctx.respectScreenScale = false;
  ctx.drawImageInRect(img, new Rect(0, 0, w, h));
  return ctx.getImage();
}

async function postPhoto(c, img, caption) {
  const jpeg = Data.fromJPEG(resize(img, MAX_UPLOAD));
  const now = Date.now();
  const d = new Date(now);
  const slug = c.name.toLowerCase().replace(/[^a-z0-9]/g, "").slice(0, 20) || "me";
  const mm = String(d.getUTCMonth() + 1).padStart(2, "0");
  const path = `photos/${d.getUTCFullYear()}/${mm}/${now}_${slug}.jpg`;
  await put(c, path, { message: `${c.name}: photo`, content: jpeg.toBase64String() });

  const post = { path, by: c.name, caption: caption || "", ts: now };
  for (let attempt = 0; attempt < 3; attempt++) {
    const { feed, sha } = await readFeed(c);
    const next = [post, ...feed.filter((p) => p.path !== path)].slice(0, MAX_FEED);
    const payload = {
      message: `${c.name} posted`,
      content: Data.fromString(JSON.stringify(next, null, 2)).toBase64String(),
    };
    if (sha) payload.sha = sha;
    try { await put(c, FEED, payload); break; }
    catch (e) { if (![409, 422].includes(e.status) || attempt === 2) throw e; }
  }
  fm.write(LATEST_IMG, jpeg);
  fm.writeString(LATEST_META, JSON.stringify(post));
  return post;
}

// ------------------------------------------------------------------ cache + sync

function cachedLatest() {
  try {
    if (fm.fileExists(LATEST_IMG) && fm.fileExists(LATEST_META)) {
      return { post: JSON.parse(fm.readString(LATEST_META)), image: fm.readImage(LATEST_IMG) };
    }
  } catch (e) {}
  return { post: null, image: null };
}

async function syncLatest(c) {
  const { feed } = await readFeed(c);
  const post = feed[0];
  if (!post) return { post: null, image: null, isNew: false, feed };
  const cached = cachedLatest();
  if (cached.post && cached.post.path === post.path && cached.image) {
    return { ...cached, isNew: false, feed };
  }
  const data = await download(c, post.path);
  fm.write(LATEST_IMG, data);
  fm.writeString(LATEST_META, JSON.stringify(post));
  return { post, image: Image.fromData(data), isNew: !!cached.post, feed };
}

async function photoFor(c, post, maxSide) {
  const file = fm.joinPath(dir, post.path.replace(/\//g, "_"));
  if (!fm.fileExists(file)) fm.write(file, await download(c, post.path));
  const img = fm.readImage(file);
  return maxSide ? resize(img, maxSide) : img;
}

// ------------------------------------------------------------------ widget

/** Darken the bottom of the photo so the white text is readable. */
function withScrim(img) {
  const small = resize(img, 900);
  const { width: w, height: h } = small.size;
  const ctx = new DrawContext();
  ctx.size = new Size(w, h);
  ctx.opaque = true;
  ctx.respectScreenScale = false;
  ctx.drawImageInRect(small, new Rect(0, 0, w, h));
  const steps = 24, start = h * 0.55, band = (h - start) / steps;
  for (let i = 0; i < steps; i++) {
    ctx.setFillColor(new Color("#000000", 0.03 * (i + 1) * 0.9));
    ctx.fillRect(new Rect(0, start + i * band, w, band + 1));
  }
  return ctx.getImage();
}

function emptyWidget(w, text) {
  w.addSpacer();
  const t = w.addText(text);
  t.font = Font.semiboldSystemFont(14);
  t.textColor = Color.white();
  t.centerAlignText();
  w.addSpacer();
}

async function buildWidget(c) {
  const w = new ListWidget();
  w.backgroundColor = new Color("#1C1C1E");
  w.url = scriptUrl("view");
  w.refreshAfterDate = new Date(Date.now() + 5 * 60 * 1000); // a hint; iOS decides the real timing
  if (!c) { emptyWidget(w, "Tap to set up UsTwo 💌"); return w; }

  let r;
  try { r = await syncLatest(c); }
  catch (e) { r = { ...cachedLatest(), isNew: false }; }

  if (!r.post || !r.image) { emptyWidget(w, "No photos yet 💌"); return w; }

  // Local notification when your partner posted something new (iOS may batch these).
  if (r.isNew && r.post.by !== c.name) {
    try {
      const n = new Notification();
      n.title = `${r.post.by} sent you a photo 💌`;
      n.body = "Tap to see it";
      n.openURL = scriptUrl("view");
      await n.schedule();
    } catch (e) {}
  }

  w.backgroundImage = withScrim(r.image);
  w.setPadding(10, 12, 10, 12);
  w.addSpacer();
  const row = w.addStack();
  row.centerAlignContent();
  const who = row.addText(`${r.post.by} · `);
  who.font = Font.semiboldSystemFont(12);
  who.textColor = Color.white();
  const when = row.addDate(new Date(r.post.ts));
  when.applyRelativeStyle(); // updates by itself ("5 min", "2 hr")
  when.font = Font.semiboldSystemFont(12);
  when.textColor = Color.white();
  if (r.post.caption && config.widgetFamily !== "small") {
    const cap = w.addText(r.post.caption);
    cap.font = Font.systemFont(12);
    cap.textColor = Color.white();
    cap.lineLimit = 2;
  }
  return w;
}

// ------------------------------------------------------------------ app UI

async function notice(title, message) {
  const a = new Alert();
  a.title = title;
  a.message = message || "";
  a.addAction("OK");
  await a.presentAlert();
}

async function setup(existing) {
  const a = new Alert();
  a.title = "Set up UsTwo 💌";
  a.message = "Connect the private GitHub repo you both share.";
  a.addTextField("owner/repo or GitHub link", existing ? `${existing.owner}/${existing.repo}` : DEFAULT_REPO);
  a.addSecureTextField("GitHub access token", "");
  a.addTextField("Your name (shown on partner's widget)", existing ? existing.name : "");
  a.addSecureTextField("New PIN (4+ digits)", "").setNumberPadKeyboard();
  a.addAction("Connect");
  a.addCancelAction("Cancel");
  if ((await a.presentAlert()) === -1) return null;

  const repo = parseRepo(a.textFieldValue(0));
  const token = a.textFieldValue(1).trim();
  const name = a.textFieldValue(2).trim();
  const pin = a.textFieldValue(3).trim();
  if (!repo) { await notice("Repo should look like owner/name"); return null; }
  if (!token || !name) { await notice("Fill in every field"); return null; }
  if (!/^\d{4,12}$/.test(pin)) { await notice("PIN must be 4–12 digits"); return null; }

  const c = { ...repo, token, name, pin };
  try { await checkAccess(c); }
  catch (e) { await notice("Couldn't connect", e.message); return null; }
  saveConfig(c);
  Keychain.set(LOCK_KEY, JSON.stringify({ fails: 0, until: 0 }));
  await notice("Connected ✅", "Now add the UsTwo widget to your home screen (see the guide).");
  return c;
}

async function unlock(c) {
  let lock = { fails: 0, until: 0 };
  try { if (Keychain.contains(LOCK_KEY)) lock = JSON.parse(Keychain.get(LOCK_KEY)); } catch (e) {}
  if (Date.now() < lock.until) {
    await notice("Locked 🔒", `Too many wrong PINs. Try again in ${Math.ceil((lock.until - Date.now()) / 1000)}s.`);
    return false;
  }
  const a = new Alert();
  a.title = "UsTwo 🔒";
  a.message = "Enter your PIN";
  a.addSecureTextField("PIN", "").setNumberPadKeyboard();
  a.addAction("Unlock");
  a.addCancelAction("Cancel");
  if ((await a.presentAlert()) === -1) return false;
  if (a.textFieldValue(0) === c.pin) {
    Keychain.set(LOCK_KEY, JSON.stringify({ fails: 0, until: 0 }));
    return true;
  }
  lock.fails += 1;
  if (lock.fails >= 5) lock.until = Date.now() + Math.min(30000 * 2 ** (lock.fails - 5), 3600000);
  Keychain.set(LOCK_KEY, JSON.stringify(lock));
  await notice("Wrong PIN");
  return false;
}

function esc(s) {
  return String(s || "").replace(/[&<>"]/g, (ch) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[ch]));
}

async function showPhoto(post, img) {
  const b64 = Data.fromJPEG(img).toBase64String();
  const ago = new RelativeDateTimeFormatter().string(new Date(post.ts), new Date());
  const html = `<!doctype html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=5">
<style>
 html,body{margin:0;height:100%;background:#000;color:#fff;font:16px -apple-system,system-ui}
 img{position:fixed;inset:0;width:100%;height:100%;object-fit:contain}
 .cap{position:fixed;left:0;right:0;bottom:0;padding:18px 20px 34px;
      background:linear-gradient(transparent,rgba(0,0,0,.8))}
 b{font-weight:600}
</style></head><body>
<img src="data:image/jpeg;base64,${b64}">
<div class="cap"><b>${esc(post.by)}</b> · ${esc(ago)}${post.caption ? "<br>" + esc(post.caption) : ""}</div>
</body></html>`;
  await WebView.loadHTML(html, null, null, true);
}

async function viewLatest(c) {
  let r;
  try { r = await syncLatest(c); } catch (e) { r = cachedLatest(); }
  if (!r.post || !r.image) { await notice("No photos yet 💌", "Send the first one!"); return; }
  await showPhoto(r.post, r.image);
}

async function sendImage(c, img) {
  const a = new Alert();
  a.title = "Send this photo? 💌";
  a.addTextField("Caption (optional)", "");
  a.addAction("Send");
  a.addCancelAction("Cancel");
  if ((await a.presentAlert()) === -1) return;
  try {
    await postPhoto(c, img, a.textFieldValue(0).trim());
    await notice("Sent 💌", "It'll show on their widget at the next refresh.");
  } catch (e) {
    await notice("Couldn't send", e.message);
  }
}

async function sendFrom(c, source) {
  let img;
  try { img = source === "camera" ? await Photos.fromCamera() : await Photos.fromLibrary(); }
  catch (e) { return; } // user cancelled
  await sendImage(c, img);
}

async function memories(c) {
  let feed;
  try { feed = (await readFeed(c)).feed; }
  catch (e) { await notice("Couldn't load", e.message); return; }
  if (!feed.length) { await notice("No photos yet 💌"); return; }
  const posts = feed.slice(0, 30);

  // Download thumbnails a few at a time (cached on disk after the first time).
  const thumbs = {};
  for (let i = 0; i < posts.length; i += 6) {
    await Promise.all(posts.slice(i, i + 6).map(async (p) => {
      try { thumbs[p.path] = await photoFor(c, p, 240); } catch (e) {}
    }));
  }

  while (true) {
    let picked = null;
    const table = new UITable();
    table.showSeparators = true;
    const fmt = new DateFormatter();
    fmt.useMediumDateStyle();
    fmt.useShortTimeStyle();
    for (const p of posts) {
      const row = new UITableRow();
      row.height = 84;
      if (thumbs[p.path]) {
        const im = row.addImage(thumbs[p.path]);
        im.widthWeight = 25;
      }
      const t = row.addText(p.by, `${fmt.string(new Date(p.ts))}${p.caption ? "\n" + p.caption : ""}`);
      t.widthWeight = 75;
      row.onSelect = () => { picked = p; };
      row.dismissOnSelect = true;
      table.addRow(row);
    }
    await table.present(false);
    if (!picked) return;
    try { await showPhoto(picked, await photoFor(c, picked, null)); }
    catch (e) { await notice("Couldn't open photo", e.message); }
  }
}

async function menu(c) {
  while (true) {
    const a = new Alert();
    a.title = "UsTwo 💌";
    a.addAction("👀 Latest photo");
    a.addAction("🖼 Send from Photos");
    a.addAction("📷 Take a photo");
    a.addAction("💞 Memories");
    a.addAction("⚙️ Setup again");
    a.addCancelAction("Close");
    const i = await a.presentSheet();
    if (i === 0) await viewLatest(c);
    else if (i === 1) await sendFrom(c, "library");
    else if (i === 2) await sendFrom(c, "camera");
    else if (i === 3) await memories(c);
    else if (i === 4) { const n = await setup(c); if (n) c = n; }
    else return;
  }
}

async function runApp(c) {
  if (!c) { c = await setup(null); if (!c) return; }
  if (!(await unlock(c))) return;

  // Share sheet: Photos → Share → Run Script → UsTwo
  if (config.runsInActionExtension && args.images && args.images.length) {
    await sendImage(c, args.images[0]);
    return;
  }
  const action = (args.queryParameters || {}).action;
  if (action === "send") { await sendFrom(c, "library"); return; }
  if (action === "camera") { await sendFrom(c, "camera"); return; }
  if (action === "view") await viewLatest(c);
  await menu(c);
}

// ------------------------------------------------------------------ entry

const cfg = loadConfig();
if (config.runsInWidget) {
  Script.setWidget(await buildWidget(cfg));
} else {
  try { await runApp(cfg); }
  catch (e) { await notice("Something went wrong", e.message); }
}
Script.complete();
