# Instructions for Claude

## Project

**QuietWrist** — a minimal Android app that relays only the WhatsApp notifications that
actually alerted, so a Huawei watch (via Huawei Health) buzzes for those and nothing else.
See `README.md` for the mechanism and the device setup.

- Stack: Kotlin, plain Android views, no framework. Single `app` module, Gradle KTS.
- `minSdk 29` (needs `Ranking.getLastAudiblyAlertedMillis()`), `targetSdk`/`compileSdk 35`.
- Target device: Samsung Galaxy S24 (One UI) paired with a Huawei watch.
- Dependencies are deliberately minimal — `androidx.core` only. Keep it that way; every
  added dependency slows the CI build that is the only way an APK reaches the phone.

### Files

- `RelayService.kt` — the `NotificationListenerService`; decides relay vs. skip.
- `Relay.kt` — builds and posts QuietWrist's own notification, including the forwarded
  WhatsApp reply action.
- `Prefs.kt` — two settings plus the on-device decision log.
- `MainActivity.kt` — setup and diagnostics screen, built programmatically.

### Settled questions — do not re-litigate

- **Replying from the watch is impossible.** Huawei Health gates quick reply on an exact
  package-name match. Tested: a `com.whatsapp.quietwrist` application id behaved exactly
  like the normal one, and re-applying WhatsApp's `MessagingStyle` plus `WearableExtender`
  changed nothing. The forwarded reply action stays because it works from the phone's
  shade, but do not build another workaround for the watch.
- **Never filter on `Ranking.getLastAudiblyAlertedMillis()`.** The system stamps it around
  the moment listeners are notified, so it reads 0 for notifications that did vibrate. A
  switch that required it silently dropped real messages. It is logged, not acted on.

### Non-negotiables

- **No background work.** No polling, foreground services, wake locks, alarms, or network.
  The whole design is "wake on callback, do a few milliseconds of work, go back to sleep".
  Battery cost is the headline feature; do not regress it.
- Every relay/skip decision must be recorded via `Prefs.log`. Tuning happens on-device.

## Build & release

There is no local Android SDK on the owner's machine — **GitHub Actions is the compiler**.
`.github/workflows/build.yml` builds on every push to `main` and replaces the rolling
`latest` release, so <https://github.com/mrojala/quiet-wrist/releases/latest> is a permanent
install URL for the phone.

Signing keys live in repo secrets (`KEYSTORE_P12_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_PASSWORD`); the keystore is at `~/.android-keystores/quietwrist-release.p12`.
Never commit a keystore, password, or base64 blob.

Verify a change compiles by watching the Actions run (`gh run watch`), not by assuming.

## Git & PR workflow

Hosted under the personal GitHub account `mrojala` (remote: `git@personal:mrojala/quiet-wrist.git`,
a dedicated SSH key from `~/.ssh/config`).

**Identity:** commits must use `10807957+mrojala@users.noreply.github.com`. The work email must
never appear in a commit, a file, a certificate subject, or a CI log. `~/code/markus/.gitconfig`
sets this via `includeIf`, and it is also set in this repo's local config — check
`git config user.email` before the first commit in a fresh clone or worktree.

Work is delivered via feature branches → PRs against `main`. Name branches `feat/<slug>`.

### gh CLI: switch account before any gh command

`gh` on this machine defaults to a different account with no access to `mrojala/...`:

```bash
gh auth switch --user mrojala     # activate
gh pr create ...                  # or any other gh command for this repo
gh auth switch --user <previous>  # restore when done
```

Plain `git push` / `git pull` work regardless — they use SSH via the `personal` host alias.
Never run `gh auth logout`; it deletes the stored token.

### Commits

- Imperative subject, short body when the "why" is non-obvious.
- Only create commits when asked.
