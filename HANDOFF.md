# Open items

Everything durable lives in `AGENTS.md` and `README.md` — read those first; they are kept
accurate. This file is only the perishable part: what is **unverified** and what is
**still broken** as of release **0.1.31**. Delete sections as they get resolved.

## Unverified

Shipped but never confirmed on the device. Don't assume any of it works:

- Crash capture (`CRASH` lines) and the guarded post (`FAILED` lines) (0.1.27). Never
  observed firing — which is also what success looks like, so it proves nothing either way.

Verified and working: the filter in both directions (`vib=true` relays and buzzes,
`vib=false` skips), coalescing, dedupe, the release pipeline, and — since 0.1.31 — keeping
the wrist clear while the phone is in use.

## Resolved: clearing while the phone is unlocked

Took three attempts and is worth knowing about, since the same trap is easy to fall into
again. `ACTION_USER_PRESENT` alone was never enough: it fires only on the unlock
*transition*, so a message arriving while the phone was already unlocked was relayed and
then sat there. 0.1.29 added a `phoneInUse()` check before posting; 0.1.31 stopped betting
on one broadcast and hung the same check off four independent triggers. That combination
works — reported stable with no further issues.

The lesson: a single system broadcast is a single point of failure you cannot debug from a
phone. The trigger that made it reliable is the one that cannot fail to be delivered — the
listener's own notification callbacks, which already arrive for every app.

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
