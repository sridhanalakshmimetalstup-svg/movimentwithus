# 💌 UsTwo — a private photo widget for two

You send a photo → it shows up on your partner's home-screen widget. Tap the widget → PIN → full-screen photo.

| | Android (her) | iPhone (you) |
|---|---|---|
| App | `UsTwo.apk` (built by GitHub Actions) | Scriptable script `ios/UsTwo.js` |
| Home-screen widget | ✅ native widget | ✅ Scriptable widget |
| PIN lock | ✅ (hashed, 5 wrong tries → lockout) | ✅ (stored in iOS Keychain, same lockout) |
| Send photo | Camera, Gallery, or Share → UsTwo | Shortcut, Back Tap, or Share → Run Script |
| New-photo notification | ✅ | ✅ (when the widget refreshes) |

**This one GitHub repo does everything:**

```
movimentwithus/
├── android/            ← app source code
├── ios/                ← iPhone script + Shortcuts guide
├── .github/workflows/  ← builds the APK and publishes it under Releases
├── photos/2026/11/…    ← every photo you send (created automatically)
└── feed.json           ← the "database": newest-first list of posts (created automatically)
```

---

## ⚠️ Step 0 — make the repo PRIVATE (do this first)

Right now `movimentwithus` is **public**: anyone on the internet could see every photo you send.
GitHub → the repo → **Settings** → scroll to **Danger Zone** → **Change visibility** → **Private**.

Both apps refuse to connect to a public repo, so they won't work until you do this.

## Step 1 — put this code at the ROOT of the repo

Your first upload landed in `movimentwithus/movimentwithus/` and lost the hidden `.github` folder,
so GitHub never built an APK. Use git (the web uploader skips folders starting with a dot):

```bash
git clone https://github.com/sridhanalakshmimetalstup-svg/movimentwithus.git
cd movimentwithus
git rm -r --quiet movimentwithus          # remove the old nested copy
# unzip UsTwo.zip, then copy its CONTENTS (including the hidden .github folder) here:
cp -r /path/to/unzipped/UsTwo/. .
git add -A
git commit -m "UsTwo app at repo root"
git push
```

After this, the repo root must show `android/`, `ios/`, `.github/` and `README.md` directly.

## Step 2 — get the APK

The push starts **Actions → Build Android APK** (about 4–6 minutes).
When it's green, open **Releases** in the repo → download **UsTwo.apk**.

On her Android phone: open the Release page (signed in to GitHub) → download → tap it → allow "Install unknown apps" for the browser → Install.
Every later code change builds a new version that installs over the old one (same signing key).

## Step 3 — make two access tokens (one per phone)

Must be done while logged in as the repo owner (`sridhanalakshmimetalstup-svg`).

GitHub → **Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token**

- Name: `UsTwo Android` (then again for `UsTwo iPhone`)
- Expiration: up to 1 year, and set a reminder to renew it
- Repository access: **Only select repositories → `movimentwithus`**
- Permissions → Repository permissions → **Contents: Read and write** (Metadata: Read is added automatically)

Two tokens means if one phone is lost, you revoke only that one.
Send each token to its phone however you like, but delete the message after setup.

## Step 4 — set up each phone

**Android:** open UsTwo → Repo `sridhanalakshmimetalstup-svg/movimentwithus` → paste token → your name → PIN → **Connect**. Allow notifications.
Then long-press the home screen → **Widgets** → **UsTwo** → drag it out.

**iPhone:** follow [`ios/SHORTCUTS.md`](ios/SHORTCUTS.md).

---

## How fast does a photo arrive? (straight answer)

**It's not instant.** It isn't instant on either phone, and that's a limit of the free setup, not a bug:

- **Android** checks GitHub every **15 minutes** (Android's minimum for background work) and also when the app opens.
- **iPhone**: iOS decides when Scriptable widgets refresh. Usually every **5–15 min**, sometimes slower on low battery.
- Opening the app or tapping the widget always loads the newest photo right away.

Real Locket feels instant because it uses push notifications. Doing that here would mean adding Firebase (free, Android side)
and a native iOS app, which needs a **$99/year Apple Developer account**. On iPhone, Scriptable is the only no-cost way to get a home-screen widget.

## Security: what the PIN does and doesn't do

- ✅ The PIN stops someone who picks up your unlocked phone from opening the app.
- ✅ Photos sit in a **private** repo, reachable only with your tokens. Tokens are encrypted on Android (Keystore) and kept in the iOS Keychain.
- ⚠️ **The widget shows the latest photo on the home screen with no PIN.** That's what a widget is for. Don't post anything you wouldn't want visible on the home screen.
- ⚠️ Photos are **not end-to-end encrypted**. The repo owner account (and GitHub itself) can see them. Turn on 2FA for that GitHub account.
- ⚠️ Anyone with push access to this repo can change the app's code. Don't add collaborators.

## Limits

- Repo size: GitHub recommends keeping repos under ~5 GB. At ~300–500 KB per photo that's roughly **10,000+ photos**.
- `feed.json` keeps the last 200 posts. Older photos stay in `photos/`; they just don't appear under "Memories".
- Actions: photo uploads don't trigger builds, so the 2,000 free private-repo minutes per month are only used when the app code changes.
- Google is rolling out "developer verification" for sideloaded apps. It isn't enforced in India yet (planned globally for 2027). When it is, install via the "advanced flow" or `adb install UsTwo.apk`.
