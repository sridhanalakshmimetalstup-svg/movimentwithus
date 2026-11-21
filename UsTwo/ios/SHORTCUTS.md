# iPhone setup — widget + Shortcuts

Everything on iPhone runs inside **Scriptable** (free on the App Store). No Apple developer account needed.

## 1. Install the script (2 min)

1. Install **Scriptable** from the App Store.
2. Open Scriptable → tap **+** → paste the full contents of `ios/UsTwo.js`.
3. Tap the title at the top and rename the script to exactly **`UsTwo`** (the Shortcuts below use that name).
4. Tap **Done**, then tap the script once to run it. It asks for:
   - Repo: `sridhanalakshmimetalstup-svg/movimentwithus`
   - Your GitHub token (the iPhone token from the main README)
   - Your name
   - A PIN
5. It says **Connected ✅** when it works.

## 2. Add the home-screen widget

1. Long-press the home screen → **Edit** → **Add Widget** → search **Scriptable**.
2. Choose Small, Medium or Large → **Add Widget**.
3. Long-press the new widget → **Edit Widget**:
   - **Script** → `UsTwo`
   - **When Interacting** → `Run Script`
4. Done. Tapping the widget opens UsTwo → PIN → the photo full-screen.

## 3. Shortcuts (one-tap send)

Make these in the **Shortcuts** app. Each one has a single action.

### "Send to UsTwo" (pick from Photos)
1. Shortcuts → **+** → **Add Action** → search **Open URLs**.
2. URL: `scriptable:///run/UsTwo?action=send`
3. Name it **Send to UsTwo** → Share → **Add to Home Screen**.

### "UsTwo Camera" (take a photo and send)
Same as above, URL: `scriptable:///run/UsTwo?action=camera`

### Send from the Photos app's share sheet
1. In Scriptable, open the `UsTwo` script → tap the **⚙︎ settings** icon (bottom).
2. **Share Sheet Inputs** → turn on **Images**.
3. Now in Photos: pick a photo → **Share** → **Run Script** → **UsTwo**.

Tip: Settings → Accessibility → Touch → **Back Tap** → Double Tap → choose *UsTwo Camera* for a "tap the back of the phone to send" shortcut. On iPhone 15 Pro and later you can put it on the **Action button** too.

## How fast does her photo show up?

iOS decides when widgets refresh. Usually 5–15 minutes, sometimes longer when the battery is low.
Opening the script (or tapping the widget) always fetches the newest photo right away.
