# Notes for Claude

**Kindroid+**: an Android app that shows kindroid.ai in a WebView, so the owner
logs in once and stays logged in. Planned next: change Kindroid's chat
background by itself, from the Kin's selfies or the owner's own pictures.

- Pure Kotlin, **no libraries** (no AndroidX), plain `Activity`, views built in
  code. Same setup as the owner's `ferisooo/Android-File-Hider`.
- minSdk 26, compile/target SDK 36, AGP 8.11.1, Kotlin 2.2.20, Gradle 8.14.3.
- Package `com.ferisooo.kindroidplus`. All code in `MainActivity.kt`.
- Kindroid's real site is **kindroid.ai** (the owner said kindroid.com; both
  are kept in the app). Login is by email code; "Sign in with Google" is
  blocked by Google inside apps.
- Code comments and UI text: short plain English for a non-technical person.

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
