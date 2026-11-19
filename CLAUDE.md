# Notes for Claude

**Kindroid+**: an Android app that shows kindroid.ai in a WebView, so the owner
logs in once and stays logged in, with the Kin's selfies taking turns as the
chat background (the selfie slideshow).

- Pure Kotlin, **no libraries** (no AndroidX), plain `Activity`, views built in
  code. Same setup as the owner's `ferisooo/Android-File-Hider`.
- minSdk 26, compile/target SDK 36, AGP 8.11.1, Kotlin 2.2.20, Gradle 8.14.3.
- Package `com.ferisooo.kindroidplus`. `MainActivity.kt` (the WebView, sign-in
  pop-ups, uploads, offline note, fast reopen, singleTask, lock timing, start
  over), `Slides.kt` (the 🖼 button and "Kindroid+ 💕" pop-up; the page
  bridge; the hidden gallery pass), `Lock.kt` (app lock), `MyPictures.kt`
  (own pictures), `Ai.kt` and `Ideas.kt` (✨ ideas with the owner's own AI key),
  and `assets/slides.js` (everything on the page).
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
- The Firestore read works with the App Check pass (the PC session checked it
  logged in, build 7). Selfie `url`s open ("!enc:") to bare storage **paths**
  ("users/<uid>/…/<id>.jpg"): Kindroid's resolver puts them on
  `https://storage.kindroid.ai/` (http(s) stays, storage2 → storage). Builds
  4-7 dropped them (found 0); `fullUrl` fixes it, and `lighter` asks Kindroid's
  resizer (`/cdn-cgi/image/width=1080,fit=scale-down,format=auto/<path>`).
- Scene setting: the location-pin button (aria-label "Scene setting") opens the
  "Set the scene" sheet; a React-controlled `<textarea maxlength=160
  placeholder="location, attire, activity, time, etc.">` (or the "Suggested
  scene" `…_suggestion-input`), saved only by Kindroid's Save button (POST
  api.kindroid.ai/v1/update-info {ai_id, current_scene}). Filled with the
  native value setter + an "input" event.
- Sheets are `[role=dialog]` / `[aria-modal]` and add no history entry.
- Kin settings ("Personality" tab, sheet on the chat page): React-controlled
  textareas by id, no auto-save ("Save Personality" footer, POST
  v1/update-info with the changed fields): kin-backstory-v2 (Personality,
  2500 subscriber / 2000), kin-memory-v2 (Key Memories, 1000 / 500),
  kin-developed-self-v2 (800), kin-durable-facts-v2 (1200),
  kin-ongoing-context-v2 (800), kin-directive-v2 (250),
  kin-example-message-v2 (750), kin-additional-context-v2 (5000 / 2500),
  kin-scene-v2 (160), kin-call-directive-v2, kin-away-directive-v2.
- Message box: `<textarea class="…_input" aria-label="Message" maxlength=4000>`;
  on phones Enter is a newline, sending is the right-hand button ("Send
  message"). The wand ("Suggest a message") is Kindroid's own reply suggestion.
- VideoSelfies: video_url (+ image_url poster), same "!enc:" + path; mp4/webm on
  storage.kindroid.ai with CORS and range requests. The owner's Kins had none
  (Nov 2026), so videos are untested against real data.
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
chosen background's opacity and blur onto an inner `dim` layer over solid
black: the owner saw Kindroid's own background "ghost" through when the whole
box copied the opacity, build 14). It sends the page's App Check pass
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
While a sheet is open the page tells the app (`bridge.sheet`): the 🖼 button
hides and back runs `__kpCloseSheet` (Escape, then a close button, then
history.back) instead of leaving the chat. WebView debugging is turned off.
**See-through message boxes** (drop-down "Boxes": Solid / See-through 55%
(default) / Very see-through 25% / Invisible (no colour, edge or shadow; the
words get a 1-2 px black outline all round, a big dark glow hugging them and
weight 600, and the slideshow picture is a little darker (`brightness(0.8)` on
`dim`, on top of Kindroid's own blur), since white words were hard to read on
bright skin (owner, builds 16 and 17); a dark radial "cloud" behind the box was
tried and looked like a smudge);
no blur, the owner found the blur made it look blurry):
a <style id="kp-bubbles">
that mixes the bubble's own colour with transparent, matched by
`[class*="_bubble-self"]` / `-other`, not bare or wrap ones. Random (every one once before repeats),
newest or oldest first; 5 s to 5 min. It reads the list again every 10 min.
`Slides.kt`: settings in prefs "slides" (on, order, seconds, button_at), read
by the page through a bridge with a made-up name per run; the page reports
what it found, shown at the bottom of the settings pop-up. The 🖼 button shows
only in a Kin's chat, sits at the right edge and can be dragged up or down.
Tested in headless Chromium on a pretend chat page (fake Firestore, CryptoJS
values, md5 against Node), not against the real site.

## What the slideshow shows (build 9)

Each Kin's photos ("Selfies") and video selfies ("VideoSelfies") are read
newest first in one go (no per-order lists; "oldest" turns the list round),
plus my pictures. Items are `{kind: photo|video, url, poster, key, at}`;
`itemsFor` picks what the "Show" choice (photos / videos / my pictures) and
"⭐ Favorites only" allow (no favorites starred: all show), sorted by `at`.
A video slide is a muted, looping `<video>` in the layer (poster as its
background), loaded to `canplay` first (15 s at most), shown at least one play
(up to a minute); the old layer's video is stopped and taken out after the
fade. Favorites: `localStorage kp-favs:<kin>` (keys are the picture's own
address); `__kpOpenFavorites` is a page overlay (role=dialog, so the 🖼 button
hides and back = Escape closes it) with every item, tap to star;
`__kpStarCurrent` stars the one showing. My pictures (`MyPictures.kt`): picked
with the Photo Picker (Android 13+) or the file picker, copied into
files/mine/<uuid>.jpg (≤ 2048 px, turned upright), served to the page by
`shouldInterceptRequest` at https://kindroid.ai/__kindroidplus/mine/<name>,
listed through `bridge.myPictures()` (re-read when `mineGen` changes).

## 🔒 App lock (`Lock.kt`)

PIN (4-12 numbers; a saved one opens as soon as its length is typed), pattern
(3x3 dots, at least 4) or password (at least 4 characters, NFC). Only a salted
PBKDF2-HMAC-SHA256 hash (120,000 rounds) in prefs "lock". Five wrong tries:
wait 30 s, doubling up to 5 min (kept across restarts). It locks at start and
when the app comes back after being away (right away, or after 1 / 5 minutes),
not after the app's own pickers (`awayForPicker`). The lock screen is a view on
top of everything; pop-up windows can't open over it; with a lock the
recent-apps picture is hidden (Android 13+). "Forgot?" = start over: clears the
page's storage, cookies, WebStorage, all prefs and my pictures, then recreates
(the Kindroid account is untouched; log in again).

## ✨ Ideas (owner's own AI key)

The owner picks Claude, ChatGPT, Gemini, Grok or DeepSeek and pastes a key
(prefs "ai", app-only storage, allowBackup off; checked by listing models,
which costs nothing). Buttons float over Kindroid's boxes (added to
`document.body`, not inside React's elements; pointer-events auto and
stopPropagation so a modal sheet doesn't close or swallow the tap; with a sheet
open only its own boxes get one): "✨ Ideas" on the scene box and the Kin's
settings (`FIELDS_BY_ID`, limit from the box's own maxlength), "💬 What to
say?" above the message box. The context (`ideaContext`: Kin name, the box's
text, last 6 or 10 messages, the Kin's other settings) goes to `Ideas.kt`:
scene → one scene ≤ 150 chars; field → the whole box text (improved if it had
some, paragraphs kept, ≤ 90% of the limit); reply → three replies to tap. "Use
it" fills the box (`__kpPut(text, kind)`); Kindroid's Save / Send still decide.
Also from the 🖼 pop-up ("Write a scene ✨", "AI: …").
"Again" adds what it already wrote in that pop-up (`Ideas.avoid`, the last 4,
or 9 replies) and asks for something clearly different: on DeepSeek the four
tries came out nearly the same (build 8). DeepSeek also gets temperature 1.3
(only DeepSeek: OpenAI's newer models, Gemini 3.6+ and Claude refuse sampling
settings). The Kin's name is the innermost visible `…_name` in the header
(an outer one read "avaava").

Fixed after the PC session's build 10 and 15 tests: the ✨ pill covered text in
big boxes (padding-bottom didn't help in a box that scrolls), so it now sits in
the box's own title row, just left of its "N/2500" counter (`counterOf`), small
(12 px); with no counter, just under the box. The 🖼 button also hides
while the Kin's menu has the chat card peeked aside (`[class*="_card-peeked"]`,
`bridge.cover`; back there is left as Kindroid has it); "Remove key" only shows
when a key is saved. While the Kin's menu is open (chat card peeked), the
slideshow box turns plain dark (`darkenForMenu`: Kindroid's black, pictures
hidden, videos paused, no new slides): the owner's screenshot showed the menu's
words unreadable over a selfie. The PC session's emulator lost its Kindroid login after
updating 7 → 10, but only "Forgot?" touches the login in this app and it wipes
everything (other site data was still there), so the cause wasn't found here.

## The chat's bars (Bars tile: Slim / Clear / Hide, all on by default)

The 🖼 pop-up is a 3 by 3 grid of same-size tiles (`tile`, `menu`): Order,
Speed, Boxes / Show, ⭐ Favorites, 📱 Pictures / ✨ AI ideas, Bars, 🔒 Lock.
From kindroid.ai (mapped and tried live by the PC session): top
`…_header-overlay > header.…_row-wallpaper` (dark glass, 44 px buttons), bottom
`…_input-overlay > …_wrapper-wallpaper > …_bar` (Message box, + / wand / mic,
"Chatting as"); both overlay a full-height list that keeps room for them
itself (a ResizeObserver on the composer). `<style id="kp-bars">`: Slim (less
padding, 36 px buttons and avatar), Clear (no glass or blur, words and icons
with a shadow, buttons a faint dark disc), and `html.kp-bars-away` slides both
off with the separate CSS `translate` (adds to Kindroid's own keyboard lift).
`barsTick`: away after 4 s without a touch; never while typing (focus or text
in the Message box, `kb-open`, composer data-attributes), recording, a reply
coming (button labels), attachments, a sheet or the Kin's menu; a new message
brings them back; while away a tap brings them back and is swallowed (it
doesn't open a message), a scroll leaves them away; the 💬 pill and the 🖼
button hide with them.

Back (`__kpBack`, from `Slides.back`): closes a sheet (`__kpCloseSheet`) or the
Kin's menu (its own `…_card-tap-close` on the peeked chat card, else Escape,
then history.back); only when nothing was open does the app go back a page or
hide (build 15 hid the app from the Kin's menu). The app's own pop-ups take the
window focus (`onWindowFocusChanged` → `Slides.hold` → `__kpHold`): the bars
stay while one is open (they used to slide away behind the 🖼 pop-up and the
next tap was swallowed). While away, nothing inside the bars takes a touch.

## Shorter voice bars

Kindroid's voice player (module eArjsW_: `…_card` in a `…_voice-drawer` with
···, ▷, the waveform `[role=slider][aria-label="Seek voice audio"]`, 0:00)
fills the bubble; bars are counted from the waveform's width (ResizeObserver,
width/6). `<style id="kp-voice">` fixes the waveform at 140 px (the card stops
growing); seeking uses the slider's own size. Always on.
`Ai.kt`: plain HttpURLConnection, never through the page. Claude: Messages API,
`claude-opus-5-5`, effort low, `fallbacks: "default"` with beta
`server-side-fallback-2026-07-01`, anthropic-version 2026-10-21, refusal →
plain words. ChatGPT / Grok / DeepSeek: chat completions (no temperature or
token limits). Gemini: generateContent with `x-goog-api-key`, thoughts left
out. For the non-Claude ones the model is picked from the key's own model list
(`pickModel`: newest general one), remembered, and picked again if it goes
away; a typed model is never swapped. Tested on the JVM against pretend
servers (request shapes, picking, errors), not against the real companies.

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
