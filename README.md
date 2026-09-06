# QuietWrist

A tiny Android app that makes a Huawei watch buzz **only** for the WhatsApp messages
that were meant to buzz.

## The problem

Huawei Health decides what reaches the watch **per app**, not per chat. So a muted
group still vibrates your wrist, even though the phone stayed silent for it.
WhatsApp's own per-chat mute settings never make it across.

## How it works

You cut Huawei Health off from WhatsApp and point it at QuietWrist instead:

```
WhatsApp ──> Android notification ──> QuietWrist (filters) ──> new notification ──> Huawei Health ──> watch
```

QuietWrist runs a `NotificationListenerService`, looks at each WhatsApp
notification's **effective importance** (`Ranking.getImportance()`), and re-posts
only the ones at `IMPORTANCE_DEFAULT` or above under its own package name. A chat
you muted in WhatsApp is posted on a low-importance channel, so it is dropped and
the watch never hears about it.

The relay is posted on a **silent** channel by default: the watch buzzes, the phone
doesn't double-alert.

## Install

1. Open **https://github.com/mrojala/quiet-wrist/releases/latest** on the phone.
2. Tap **`QuietWrist.apk`**. Chrome will ask to allow installs from this source —
   allow it, then install.

Every push to `main` rebuilds and replaces that release, so the URL is permanent.
Builds are signed with a stable key, so later versions install straight over the
previous one.

## Setup (Samsung Galaxy S24, One UI)

In **QuietWrist**:

1. **Grant notification access** → enable QuietWrist in the list.
2. Allow the notification permission prompt.
3. **Exempt from battery optimisation** → set QuietWrist to *Unrestricted*. One UI
   otherwise puts the app to sleep and the listener silently stops.
   Also check *Settings → Battery → Background usage limits → Never sleeping apps*
   and add QuietWrist.
4. **Send test notification** — confirm the watch buzzes before touching anything else.

In **Huawei Health → Notifications**:

5. Turn **off** WhatsApp.
6. Turn **on** QuietWrist.

In **WhatsApp**: leave notifications on for everything, and mute the chats you don't
want on your wrist. Muting is what QuietWrist filters on.

## Tuning

The main screen keeps a rolling log of every WhatsApp notification and why it was
relayed or skipped, with the importance level and channel id. That is the fastest
way to find out why something did or didn't reach the watch — `adb logcat` isn't an
option when the phone is in your pocket.

Controls:

- **Auto-dismiss** — how long a relayed notification stays before clearing itself.
  Defaults to 10 minutes; cycles through 1/5/10/30/60 minutes and never. Huawei Health
  mirrors dismissals, so the message leaves the watch at the same moment — which is why
  this is a delay rather than clearing the relay as soon as it is delivered. The system
  performs the expiry itself (`setTimeoutAfter`), so it costs nothing and survives the
  process being killed.
- **Also clear WhatsApp's own notification** — clears the original on the same delay so
  one message doesn't sit in the shade twice. Off by default, because it removes the copy
  you'd otherwise catch up on from the phone. Best effort: it is a delayed callback, so a
  killed process skips it.
- **Vibrate the phone too** — posts the relay on a high-importance channel instead of
  the silent one.
- **Relay everything (debug)** — ignores the filter and relays every WhatsApp message.
  Separates "the filter rejected it" from "the relay never reached the watch".
- **Log every app (debug)** — logs notifications from all packages without relaying
  them, so you can confirm the listener is receiving anything at all without waiting
  for someone to message you.

A burst of updates to one chat within 900 ms is coalesced into a single relay, and the
log says how many were folded in. WhatsApp re-posts the same notification as a message
lands, as its text grows (a streaming bot reply arrives in pieces), and as delivery
state changes; without this, one message buzzed the watch three times.

The log records `audible=` from `Ranking.getLastAudiblyAlertedMillis()`, but **nothing
filters on it**. The system stamps that field around the same moment listeners are
notified, so it frequently reads `false` for a notification that did vibrate. An earlier
version had a switch to require it and that switch silently ate real messages.

## Battery

Effectively free. There is no polling, no foreground service, no wake locks, no
network, and no background work of any kind. `NotificationListenerService` is bound
by the system and the process only runs for the few milliseconds it takes to inspect
and re-post a notification. Delivery is immediate — it's the same callback the system
uses to deliver the notification everywhere else.

The one thing that *does* cost battery is being killed and restarted repeatedly, which
is why the battery-optimisation exemption above matters.

## Replying from the watch

**You can't.** That is the price of the quiet, and it has now been tested rather than
assumed.

The app still forwards WhatsApp's own reply action — its `RemoteInput` and
`PendingIntent`, which is only a token, so firing it sends the message *as WhatsApp* —
and posts the relay as a genuine `MessagingStyle` conversation with that action mirrored
into `WearableExtender`. This does make the relay repliable **from the phone's
notification shade**. Huawei Health still renders it on the watch as plain text.

Two things were tried and failed:

- **Looking like a real chat message.** Re-applying WhatsApp's `MessagingStyle` and the
  wearable action list changes nothing, because Huawei Health does not inspect the
  notification.
- **A `com.whatsapp.quietwrist` application id.** Built to test whether the quick-reply
  whitelist was a prefix match. It is not — the check is an exact package-name match, and
  this build behaved identically to the standard one.

### The identity experiment — tested, and it backfires

[Huawei's own documentation][huawei-reply] names exactly four repliable sources: **SMS,
WhatsApp, Messenger, Telegram**. So the whitelist is short and exact, and the obvious
last idea was to build QuietWrist *under one of those package names*. The reasoning
looked sound: replying to another app's notification is only possible through
`RemoteInput` and its `PendingIntent`, so Huawei Health cannot hold a Messenger-specific
reply path — it must fire whatever action the notification carries, and QuietWrist
forwards WhatsApp's.

It was tried with `com.whatsapp.w4b` and then, after uninstalling Messenger, with
`com.facebook.orca`. **Both made things worse**, and the `orca` run isolated why:

| Build | Test notification | Relayed WhatsApp message |
| --- | --- | --- |
| `com.mrojala.quietwrist` | reaches the watch | reaches the watch, no reply option |
| `com.facebook.orca` | reaches the watch | **never reaches the watch** |

The Huawei Health toggle was confirmed on, and the relay was confirmed posted on the
phone. So Huawei is not blocking the package — it applies *package-specific handling* to
a name it recognises, and a WhatsApp-shaped notification arriving under Messenger's
package fails whatever parsing that path does and is dropped. The generic path an unknown
package gets is more permissive than the privileged one.

**Borrowing a whitelisted package name costs delivery and buys nothing.** Do not retry it
with the remaining candidates; the mechanism that defeats it does not depend on which
name is used.

The `application_id` workflow input survives, since it is how the experiment was run and
is the only way to reproduce the result:

```bash
gh workflow run build.yml --repo mrojala/quiet-wrist --ref main \
  -f application_id=com.facebook.orca
```

It publishes to the separate `experiment` release as `QuietWrist-EXP.apk` and never
touches `latest`. Grant notification access to only one QuietWrist at a time, or every
message relays twice. No build carrying a borrowed package name can go to the Play Store
— that is squarely against Google's impersonation policy.

[huawei-reply]: https://consumer.huawei.com/en/support/content/en-us15958509/

## Build

CI does everything; a local toolchain is only needed for development.

```bash
mise use java@temurin-17     # JDK 17
gradle assembleRelease       # unsigned/debug-signed unless the keystore is present
```

Requires an Android SDK (`ANDROID_HOME` or `local.properties`) with API 35.

## Signing

Release builds are signed with a key held only in GitHub Actions secrets
(`KEYSTORE_P12_BASE64`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD`). The keystore itself lives
at `~/.android-keystores/quietwrist-release.p12` on the owner's machine — losing it means
future builds can no longer upgrade an existing install in place. Nothing signing-related
is ever committed.
