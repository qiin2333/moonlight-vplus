# Display lifecycle regressions

This standalone Android test app runs without the streaming JNI libraries or a host PC.
From the repository root, with an Android 14 emulator running:

```sh
./gradlew -p display-tests connectedDebugAndroidTest
```

On Windows use `gradlew.bat`. SDK discovery uses `ANDROID_HOME` or this directory's
ignored `local.properties`. Use `ANDROID_SERIAL` to select an emulator if needed.
The `Display lifecycle tests` workflow runs this command for relevant pull requests.

## Production source and fixture boundaries

- Gradle copies the current manager, resolver, window-mode applier, view and input-provider
  base class into `build/generated/`. Existing manager/resolver instrumented tests are reused.
- Five Game methods are inserted verbatim into `fixtures/Game.kt.template` at build time.
  Nothing is copied back into the app. Generation also checks the real `onCreate()` and
  `prepareConnection()` order: connection creation, touch initialization, then display binding.
  First launch must also create its connection callback handler before binding.
- Android displays, Presentation, WindowManager and Surface callbacks are real. Removing a
  virtual display during layout inflation exercises an actual rejected Presentation.
- The small Game host substitutes networking, decoder, controller and preference-loading
  dependencies. HDR/refresh-rate values are deliberately different fixture contracts that
  identify which display was used; they do not test device capability negotiation.
- Provider destruction is verified with a spy. This does not run a root evdev process or
  replace full-app/device testing. Per-game settings tests remain in `app/src/androidTest`.

Covered cases: initial/resumed fallback, Native dimensions after fallback or global preset
changes, reversed Native dimensions, fixed/custom/committed dimensions, accepted presentation
with natural Surface startup, local provider reuse, provider replacement cleanup, pointer
capture modes, and the existing resolver/window cleanup regressions.
