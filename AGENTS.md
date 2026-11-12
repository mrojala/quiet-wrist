# Instructions for coding agents

Named `AGENTS.md` so every tool picks it up. Claude Code loads it as project instructions
where there is no `CLAUDE.md`; don't add one alongside it.

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
- `Prefs.kt` — the settings plus the on-device decision log.
- `QuietWristApp.kt` — exists only to write crashes into that log.
- `MainActivity.kt` — setup and diagnostics screen, built programmatically.

### Settled questions — do not re-litigate

- **Huawei Health gates quick reply on an exact package-name match.** Tested: a
  `com.whatsapp.quietwrist` application id behaved exactly like the normal one, and
  re-applying WhatsApp's `MessagingStyle` plus `WearableExtender` changed nothing — it
  does not inspect the notification. The forwarded reply action stays because it works
  from the phone's shade. Do not attempt prefix tricks or notification-shape tricks again.
- **Borrowing a whitelisted package name backfires — do not retry it.** Building under
  `com.whatsapp.w4b` and then `com.facebook.orca` (Messenger uninstalled, Huawei toggle
  confirmed on) did not unlock replies; it stopped relayed messages reaching the watch at
  all, while a plain test notification from the same build still arrived. Huawei applies
  package-specific parsing to names it recognises, and a WhatsApp-shaped notification fails
  it. The generic path an unknown package gets is more permissive than the privileged one,
  so `com.mrojala.quietwrist` is the best identity available. The `application_id` workflow
  input stays only so the result can be reproduced.
  `com.whatsapp.w4b` was the strongest candidate — WhatsApp Business posts WhatsApp-shaped
  notifications, the exact shape we relay — and it failed identically, with its toggle
  confirmed on. Do not retry with Telegram or any other name.
- **One untested avenue remains**, and it is the only design that preserves replying:
  leave WhatsApp *enabled* in Huawei Health so native quick reply keeps working, and have
  QuietWrist `cancelNotification()` the muted chats fast enough that the watch never
  buzzes. It races Huawei's BLE push and will probably lose, but nothing else can give
  both quiet and reply.
- **Keeping the wrist clear while the phone is in use took three attempts; don't unpick it.**
  `ACTION_USER_PRESENT` alone fails, because it fires only on the unlock *transition* — a
  message arriving while the phone was already unlocked got relayed and then sat there. The
  fix is two-part: check `phoneInUse()` *before* posting, and hang that same check off
  several independent triggers rather than one broadcast. What made it reliable is the
  trigger that cannot fail to be delivered — the listener's own notification and ranking
  callbacks, which already arrive for every app on the phone. Confirmed stable since 0.1.31.
- **The filter needs both importance and channel vibration.** WhatsApp gives every chat
  with custom notification settings its own channel (named after the JID, e.g.
  `358504907785-1482932896@g.us_69`). A muted chat drops below `IMPORTANCE_DEFAULT`, but a
  chat left audible with vibration off still arrives at `IMPORTANCE_DEFAULT` — only
  `NotificationChannel.shouldVibrate()` catches that one. `Ranking.getChannel()` is public
  API from 26, so it is available at `minSdk 29`.
- **Never filter on `Ranking.getLastAudiblyAlertedMillis()`.** Observed: it has read 0 for
  every notification seen on this phone, including relayed ones that demonstrably alerted
  the watch — an on-device log covering hours never once showed it non-zero. A switch that
  required it silently dropped real messages. The cause is *not* established; the leading
  guess is that the system stamps it around the moment it notifies listeners, so the
  listener reads it before it is written. Treat the observation as settled and the
  explanation as a hypothesis. It is logged, not acted on.

### Non-negotiables

- **No background work.** No polling, foreground services, wake locks, alarms, or network.
  The whole design is "wake on callback, do a few milliseconds of work, go back to sleep".
  Battery cost is the headline feature; do not regress it.
- Every relay/skip decision must be recorded via `Prefs.log`. Tuning happens on-device.
  **A notification dropped without a log line is the worst failure this app has**: the
  `require-audible` switch silently ate real messages for hours and four of the filter's
  early returns logged nothing, so there was no way to tell. Never add a silent `return`.
- **Nothing may crash the listener.** `notify()` re-posts another app's MessagingStyle,
  including its avatars and history — unknown size and shape. Keep the post guarded and
  log what it throws; a crash loop costs battery and silently stops relaying. The app did
  crash occasionally with WhatsApp open before 0.1.27 added that guard, and has not since.
  Note that the guard turns such a crash into a `FAILED` log line, so "no crashes" and
  "the guard is catching something" look identical from outside — if it matters, check the
  log for `FAILED`. The standing suspect was `TransactionTooLargeException` from those
  avatars, never confirmed; if it ever recurs, wait for the log line before acting, because
  a confident theory about this exact code path already cost one wasted cycle.
- **A relay is cleared by an event, never a timer**: the phone being in use, the source
  notification being withdrawn, or a tap. An auto-dismiss delay and a "clear WhatsApp's own
  notification" option both existed and were removed once unlocking cleared relays — they
  covered the same ground with more knobs. Don't reintroduce a timer.

## Build & release

There is no local Android SDK on the owner's machine — **GitHub Actions is the compiler**.
`.github/workflows/build.yml` builds on every push to `main` and replaces the rolling
`latest` release, so <https://github.com/mrojala/quiet-wrist/releases/latest> is a permanent
install URL for the phone.

Signing keys live in repo secrets (`KEYSTORE_P12_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_PASSWORD`); the keystore is at `~/.android-keystores/quietwrist-release.p12`.
Never commit a keystore, password, or base64 blob.

Verify a change compiles by watching the Actions run (`gh run watch`), not by assuming.

There are **no tests**. A run is ~38s of Gradle and ~70s of steps, but every change builds
twice — once for the PR check, once on `main` to publish — so a one-line change costs
several minutes. Dropping the `pull_request` trigger would halve that and is safe, since
the release step is gated on the build succeeding: a broken `main` publishes nothing. It
has been proposed and not yet decided.

## Git & PR workflow

Hosted under the personal GitHub account `mrojala` (remote: `git@personal:mrojala/quiet-wrist.git`,
a dedicated SSH key from `~/.ssh/config`).

**Identity:** commits must use `10807957+mrojala@users.noreply.github.com`. The work email must
never appear in a commit, a file, a certificate subject, or a CI log. `~/code/markus/.gitconfig`
sets this via `includeIf`, and it is also set in this repo's local config — check
`git config user.email` before the first commit in a fresh clone or worktree.

Work is delivered via feature branches → PRs against `main`. Name branches `feat/<slug>`.

PRs are squash-merged, so a branch cut from a worktree *before* an earlier squash lands
will conflict with `main` afterwards even when the trees are identical. Don't fight the
rebase: re-cut from `origin/main`, re-apply the diff (`git diff origin/main HEAD` →
`git apply --3way`), and close the stale PR.

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
