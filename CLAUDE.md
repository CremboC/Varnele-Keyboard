This is Varnelė Klaviatūra, a fork of HeliBoard with Varnelė (rule-based Lithuanian
checker) built in. App ID dev.imbrasas.varnele.keyboard (.debug for debug builds); APK
app/build/outputs/apk/debug/VarneleKlaviatura_<version>-debug.apk (version 1.0). Internal Java/Kotlin
packages stay helium314.keyboard.*. Lithuanian is enabled by default
(SubtypeSettings.getDefaultEnabledSubtypes); credits in README.md and the About screen. The project notes live in the varnele repo: working rules, commands and gotchas in its AGENTS.md (loaded below); the keyboard's design and release notes in `~/git/varnele/docs/keyboard.md`, measurements in `docs/numbers.md`, the backlog in `docs/backlog.md` there (read on demand; every `docs/…` path in AGENTS.md is in `~/git/varnele`):

@../varnele/AGENTS.md

Fork-specific files (everything else is upstream HeliBoard):
- app/src/main/java/dev/imbrasas/varnele/Native.kt (JNI bridge, name must not change)
- app/src/main/java/helium314/keyboard/latin/dictionary/VarneleDictionary.kt
- app/src/main/java/helium314/keyboard/latin/inputlogic/VarneleSentenceFixer.kt
- hooks: DictionaryFactory.getMainDictsForLocale, InputLogic.handleSeparatorEvent (end)
- swipe (Varnelė's gesture decoder, no Google library needed for Lithuanian): MainKeyboardView.setKeyboard
  (key centres to VarneleDictionary.setGestureKeyboard), InputLogic.onStartBatchInput (sentence fixer after
  the previous gesture's word), BinaryDictionary.getSuggestions (declines gestures without the library: the
  native gesture policy is null), SettingsValues.mGestureInputEnabled + the gesture settings screens
  (VarneleDictionary.gesturesFor / gesturesAvailable instead of JniUtils.sHaveGestureLib)
- app/build.gradle.kts: cargoBuildVarneleHost builds the host `varnele` binary before the export
  (the export builds its count tables with `varnele fst`)
- app/build.gradle.kts: exportVarneleData + cargoBuildVarnele tasks; proguard keep rule

Remotes: `origin` = upstream HeliBorg/HeliBoard (pull only, never push); `fork` =
github.com/CremboC/Varnele-Keyboard (was CremboC/HeliBoard), branch `main` (push here).
Upstream updates: merge `origin/main` into `main`; never use GitHub's "Sync fork" discard.
HeliBoard's AI_USAGE.md prohibits AI-generated upstream PRs: keep this work in the fork.

Release / Google Play (CI): `.github/workflows/varnele-release.yml` runs on **every push to `main`** and on
manual dispatch, and builds the signed `nouserlib` bundle and APK. The details (toolchain, deploy key, signing
secrets, upload keystore, local signed build) are in "Release builds and Google Play" in
`~/git/varnele/docs/keyboard.md`. What must not break:
- versionCode = 10000 + GitHub run number, above upstream's codes: when merging upstream, new
  `oldVersion <= N` migrations (N < 10000) will NOT run on upgrades of our installs: check them. If the
  workflow is renamed or recreated the run number restarts: raise the offset above the last uploaded code.
- The upload keystore and its passwords live in `~/.keystores/` and never go into a repo.
- Upstream's build-debug-apk / build-test-auto workflow files stay untouched for clean merges.
