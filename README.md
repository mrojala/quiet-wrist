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
2. Tap the **`QuietWrist-standard-*.apk`** asset. Chrome will ask to allow installs
   from this source — allow it, then install.

(`QuietWrist-masquerade-*.apk` is the quick-reply experiment described below. It
installs alongside the standard build; ignore it unless you're testing that.)

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

Two switches:

- **Require actual sound/vibration** — additionally require that the system says the
  notification really alerted (`Ranking.getLastAudiblyAlertedMillis()`). Stricter, but
  relays nothing while the ringer is silenced or Do Not Disturb is on. Off by default.
- **Vibrate the phone too** — posts the relay on a high-importance channel instead of
  the silent one. Off by default.

## Battery

Effectively free. There is no polling, no foreground service, no wake locks, no
network, and no background work of any kind. `NotificationListenerService` is bound
by the system and the process only runs for the few milliseconds it takes to inspect
and re-post a notification. Delivery is immediate — it's the same callback the system
uses to deliver the notification everywhere else.

The one thing that *does* cost battery is being killed and restarted repeatedly, which
is why the battery-optimisation exemption above matters.

## Replying from the watch

Expect to lose wrist replies in exchange for the quiet. Huawei Health whitelists quick
reply by **package name** (`com.whatsapp`, `org.telegram.messenger`, …), so anything
QuietWrist posts is most likely shown as a plain, non-repliable notification. The only
guaranteed way to keep replies is to leave WhatsApp enabled in Huawei Health — which is
the setup you're trying to get away from.

That said, three things are stacked in the app to give it the best shot, since none of
them cost anything:

1. **The reply action is forwarded verbatim.** WhatsApp's `RemoteInput` and its
   `PendingIntent` are copied onto the relay. A `PendingIntent` is just a token, so
   firing it sends the message *as WhatsApp*. Replying from the phone's shade already
   works because of this.
2. **The relay is posted as a real conversation.** The original `MessagingStyle` —
   sender, group name, message history — is extracted and re-applied, and the action is
   also added to the `WearableExtender` list. Companion apps that decide by inspecting
   the notification, rather than by package name, see a genuine chat message.
3. **A `masquerade` build.** Same app under the application id `com.whatsapp.quietwrist`.
   If Huawei Health's whitelist check is a prefix or substring match rather than an exact
   one, this build inherits WhatsApp's privileges. Install it next to the standard build
   and compare. It is a coin flip on someone else's implementation detail — most such
   checks are exact — but it costs one CI build to find out.

The masquerade flavor is for your own phone only. It can never go to the Play Store: a
package name that reads as WhatsApp's is squarely against Google's impersonation policy.

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
