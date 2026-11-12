# Open items

Everything durable lives in `CLAUDE.md` and `README.md` — read those first; they are kept
accurate. This file is only the perishable part: what is **unverified** and what is
**still broken** as of release **0.1.31**. Delete sections as they get resolved.

## Unverified

Shipped but never confirmed on the device. Don't assume any of it works:

- Skipping a relay while the phone is unlocked and awake — `skip … phone in use` (0.1.29).
- The four clear triggers (0.1.31).
- Crash capture (`CRASH` lines) and the guarded post (`FAILED` lines) (0.1.27). Never
  observed firing.

Verified and working: the filter in both directions (`vib=true` relays and buzzes,
`vib=false` skips), coalescing, dedupe, and the release pipeline.

## Broken: relays are not cleared when the phone is unlocked

The owner wants no QuietWrist notifications present while the phone is unlocked — the
original WhatsApp notification is right there, so the copy on the wrist is redundant.
Reported not working across three attempts:

1. **0.1.27** — `ACTION_USER_PRESENT` receiver registered in `onListenerConnected()`.
2. **0.1.29** — found one real flaw: that broadcast only fires on the unlock *transition*,
   so a message arriving while the phone was already unlocked got relayed and then sat
   there. Added a `phoneInUse()` check before posting; moved registration to `onCreate()`.
   Still reported not clearing.
3. **0.1.31** — four independent triggers, all funnelling through `phoneInUse()`:
   `ACTION_USER_PRESENT`, `ACTION_SCREEN_ON`, `KeyguardManager.addKeyguardLockedStateListener`
   (API 33+), and any notification or ranking callback from any app. Untested.

### Next step is to read the log, not to write code

0.1.31 instruments this. Ask for two lines:

- At startup: `clear triggers: broadcasts=… keyguard=…` — a `false` names a trigger that
  failed to arm.
- On each clear: `cleared (USER_PRESENT)` / `cleared (keyguard)` / `cleared (in use)`.

Then it splits cleanly:

- **Triggers armed but no `cleared (…)` line ever appears** → they fire but `phoneInUse()`
  returns false when it shouldn't. Suspect `KeyguardManager.isKeyguardLocked()` on One UI;
  log `isInteractive` and `isKeyguardLocked` separately to see which half is wrong.
- **A `cleared (…)` line appears but the notifications stay** → delivery is fine, so the
  problem is `NotificationManagerCompat.cancelAll()`, or Huawei Health re-posting to the
  watch after the dismissal. Different investigation.

Trigger 4 is the one most likely to work: the service already receives those callbacks for
every app on the phone, so they cannot fail to be delivered.

**Never established:** whether "not clearing" means on the phone, on the watch, or both.
Worth asking before writing anything.

## Broken: occasional crashes with WhatsApp open

Reported a few times; no stack trace ever captured. 0.1.27 should have converted these
from crashes into `FAILED` log lines, but that is unconfirmed.

**Leading suspect, unconfirmed:** `Relay.post` re-posts WhatsApp's extracted
`MessagingStyle`, carrying its contact avatars and message history as `Icon`s. Foreign
icons and large bitmaps are a classic `TransactionTooLargeException` source on `notify()`,
and WhatsApp being open means more history in the style — which fits when it was seen.

**Do not act on this without the log line.** A confident wrong theory about this exact code
path already cost a wasted cycle: when a `com.facebook.orca` build stopped delivering, the
`MessagingStyle` was blamed and a fix half-built, then the normal build was confirmed to
carry the same style and work fine — killing the theory outright.

If the log does implicate it, synthesise a clean single-message `MessagingStyle` instead of
reusing WhatsApp's. That costs nothing real: reusing it was only ever an attempt to improve
the odds of quick reply, which is now known to be impossible.
