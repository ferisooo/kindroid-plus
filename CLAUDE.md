# Notes for Claude

**Kindroid+**: an Android app that shows kindroid.ai in a WebView, so the owner
logs in once and stays logged in, with the Kin's selfies taking turns as the
chat background (the selfie slideshow).

- Pure Kotlin, **no libraries** (no AndroidX), plain `Activity`, views built in
  code. Same setup as the owner's `ferisooo/Android-File-Hider`.
- minSdk 26, compile/target SDK 36, AGP 8.11.1, Kotlin 2.2.20, Gradle 8.14.3.
- Package `com.ferisooo.kindroidplus`. `MainActivity.kt` (the WebView, sign-in
  pop-ups, uploads, offline note, fast reopen), `Slides.kt` (slideshow settings,
  bridge, 🖼 button) and `assets/slides.js` (the slideshow on the page).
- Kindroid's real site is **kindroid.ai** (the owner said kindroid.com; both
  are kept in the app). Login is by email code; "Sign in with Google" is
  blocked by Google inside apps.
- Code comments and UI text: short plain English for a non-technical person.

## How kindroid.ai works (from its public web code, Nov 2026)

Researched by the owner's PC session from the public JS (no login), so field
names come from code; nothing was checked against a live account.
- Next.js pages router. A Kin's chat is `/v2/chat/<kin id>`; group chats
  `/v2/chat/group/<id>`. The selfie gallery is a sheet on the chat page
  (`?gallery=1&media=photo|video`).
- Chat background: `<div class="…_backdrop" aria-hidden="true">` (absolute,
  inset 0, z-index 0) holding a div with an inline `background-image`, opacity
  and blur. Class names are CSS-module hashes that change per deploy: match by
  shape. With no background set an "ambience" div shows instead.
- Selfies: Firestore `Users/<uid>/AIs/<kin>/Selfies` (videos: `VideoSelfies`),
  ordered by `timestamp`; fields url, thumbnail_url, status, deleted,
  output_moderation_status, blocked_for_native_policy, native_sfw_policy.
  A url may be `"!enc:"` + CryptoJS AES with the uid as passphrase.
  Images on storage.kindroid.ai (storage2 is rewritten to storage).
- Login: Firebase (project kindroid-ai), kept in IndexedDB
  `firebaseLocalStorageDb` / `firebaseLocalStorage`, row
  `firebase:authUser:…` → `value.uid`, `value.stsTokenManager.accessToken`.
  REST API is api.kindroid.ai with that token as Bearer. Don't call
  `v1/view-selfies` (it marks selfies seen).
- **App Check** (reCAPTCHA Enterprise, auto refresh): Firestore answers 403
  without it (build 4 on the owner's phone). The page's own token is in
  IndexedDB `firebase-app-check-database` / `firebase-app-check-store`
  (keyPath compositeKey, value {token, expireTimeMillis}); it goes along as
  `X-Firebase-AppCheck`. Never try to mint one.
- The gallery's query: orderBy timestamp desc, limit 12 (startAfter for more),
  sometimes a tags filter; database "(default)".
- Even with the App Check pass, the owner's phone still couldn't read the list
  (build 5; cause unknown, the pop-up now shows Firestore's words in brackets).
- Message bubbles (CSS module, e.g. `DSWNqq_bubble`): `…_bubble-self`
  (background `var(--chat-message-color-self, var(--color-cta))`, #964f43 dark)
  and `…_bubble-other` (`var(--chat-message-color-other,
  var(--chat-message-surface-other))`, near black), `…_bubble-bare` for media
  and voice notes, `…_bubble-wrap` around them. Kindroid has no see-through
  bubbles: its background presets (Balanced / Focused / Immersive) only set
  background opacity, blur and a fade of the top of the message list.

## Selfie slideshow

`assets/slides.js` runs on every Kindroid page (once per page, at commit and
finish; it ticks every second and follows the SPA's moves). In a Kin's chat it
reads the selfies with Firestore's REST API (`runQuery`, 100 a page, up to
1000, read only) using the page's own login, drops the ones Kindroid's gallery
hides, opens `!enc:` addresses (its own MD5 + the browser's AES-CBC), and
crossfades them in a layer of its own inside Kindroid's backdrop (copying the
chosen background's opacity and blur). It sends the page's App Check pass
(waits up to a minute for a fresh one), asks 100 at a time with only the
fields it needs, and if that's turned down (400/403) asks exactly like the
gallery (12 at a time, whole documents, up to 300). Backup: while the photo
gallery is open (a "Generate Photo Selfie" button), the big pictures around it
are kept in localStorage `kp-selfies:<kin>` (newest first, Next.js `/_next/image`
addresses unwrapped) and used when the list can't be read; a turned-down read
waits 5 minutes before asking again.
When the list can't be read the page asks the app (`needGallery`) to look
through the gallery itself: `Slides.gatherFrom` opens
`/v2/chat/<kin>?gallery=1&media=photo` in a hidden WebView behind the real one
(drawn, so it loads like it's on screen; never leaves kindroid.ai, no sound,
no pop-ups) running slides.js in "gather" mode: it scrolls the gallery's
scroller to the bottom again and again and keeps what it shows (a new one goes
right after the known one before it), done after 12 s with nothing new, 150 s,
or (for a Kin looked through before) 24 known ones in a row. At most every 6 h
per Kin (prefs `gathered_at:<kin>`), stopped when the app leaves.
Speeds 1 s to 5 min (ticks every 250 ms; the crossfade is under half a slide).
**See-through message boxes** (pop-up "Message boxes": Solid / See-through 55%
(default) / Very see-through 25%; no blur, the owner found the blur made it
look blurry; a text shadow keeps the words readable): a <style id="kp-bubbles">
that mixes the bubble's own colour with transparent, matched by
`[class*="_bubble-self"]` / `-other`, not bare or wrap ones. Random (every one once before repeats),
newest or oldest first; 5 s to 5 min. It reads the list again every 10 min.
`Slides.kt`: settings in prefs "slides" (on, order, seconds, button_at), read
by the page through a bridge with a made-up name per run; the page reports
what it found, shown at the bottom of the settings pop-up. The 🖼 button shows
only in a Kin's chat, sits at the right edge and can be dragged up or down.
Tested in headless Chromium on a pretend chat page (fake Firestore, CryptoJS
values, md5 against Node), not against the real site.

## Building

- **CI** (`.github/workflows/build.yml`) builds a release APK on every push to
  `main` or `claude/**` and publishes release `build-N`. Phone download link:
  https://github.com/ferisooo/kindroid-plus/releases/latest/download/KindroidPlus.apk
- Signed with `app/debug.keystore` (password `android`) on every build, so new
  APKs install over old ones and the login is kept. Don't replace it.
- No Android SDK in the cloud sandbox (dl.google.com is blocked). To check the
  Kotlin compiles: Kotlin 2.2.20's `kotlinc` (JetBrains' GitHub releases) and
  `org.robolectric:android-all` 16-robolectric-* from Maven Central, then
  `kotlinc -jvm-target 17 -Xnullability-annotations=@android.annotation:ignore -cp android-all.jar -d out app/src/main/java/**/*.kt`.
  Resource and layout problems only show in the CI build or on the phone.
- kindroid.ai can't be reached from the sandbox; the owner tests on the phone.
