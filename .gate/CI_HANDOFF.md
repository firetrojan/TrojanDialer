# CI handoff — build errors (2026-10-10)

State when handing build fixes to a separate agent. The app is now pushed
to `firetrojan/TrojanDialer` @ `main` and CI runs automatically on push.

## Where it stands

| Step | Result |
|---|---|
| 1. Configuration (`gradle projects`) | success |
| 1b. Task discovery (`:app:tasks`) | success |
| 2. Dependency resolution (`:app:dependencies`) | success |
| 3. `:data:core:compileDebugKotlin` | success |
| 3b. `:app:assembleDebug` | **failure** |
| 4a. `:communication:encrypted:testDebugUnitTest` | success |

Runs: 38072278100 (first, two bugs), 38072693846 (second, one bug remains).

## Fixed already

1. **Resource linking** — `app/src/main/res/values/themes.xml` inherited
   `Theme.Material3.DayNight.NoActionBar` and used the Material colour
   attributes. Those need `com.google.android.material:material`, which
   `:app` never depended on. Compose Material3 is a Kotlin API only and
   ships no XML resources. Parent is now `Theme.AppCompat.DayNight.NoActionBar`
   and the Material-only items are gone. AppCompat was already a dependency,
   so this adds no artifact. The Compose `Theme { }` in `ui/core/Theme.kt` is
   unchanged and still drives all visible theming.
2. **Summary step bash error** — lines 178-179 had an unescaped backtick
   opening a Markdown code span, so bash read the rest as a command
   substitution and the step exited 2. Escaped. All 12 `run` blocks verified
   with `bash -n`.

## Remaining failure

```
:app:processDebugResources
AAPT: error: resource mipmap/ic_launcher not found
AAPT: error: resource mipmap/ic_launcher_round not found
  at app/src/main/AndroidManifest.xml:33
```

`AndroidManifest.xml` declares
`android:icon="@mipmap/ic_launcher"` and
`android:roundIcon="@mipmap/ic_launcher_round"`, but `app/src/main/res/`
contains only `values`, `values-v23` and `values-v24`. There is no
`mipmap-*` or `drawable-*` directory anywhere in the repo, and no
`ic_launcher*` file. `values/strings.xml` and `app_name` resolve fine.

So the launcher icon assets were never committed. Options: add real
`mipmap-*` densities, or drop the two attributes and let the platform
default apply. Do not invent an icon that misrepresents the app.

## Notes for whoever picks this up

- `:app` has never assembled. This is the first real signal; treat the
  remaining errors as genuine, not as a regression from the fix above.
- After `processDebugResources` passes, later failures will be Kotlin
  compile and packaging errors that were never reachable before.
- The CI gate fails the run unless configuration, dependencies, datacore
  compile and APK assembly all succeed, so it will keep reporting honestly.
- Only `main` carries current work. `Working` is the stale 2026-10-07
  import (60 files) and should be deleted once nothing needs it.
- Do not add a `migration` or bump the Room schema version to silence a
  problem. Schema v3 is intentional.
