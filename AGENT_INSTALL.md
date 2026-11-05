# Installing VHS Launcher as the default home screen (guide for AI agents)

This file is written for a coding agent (Claude Code or similar) that has a shell with `adb`
and is asked to install VHS Launcher on someone's Android TV or Google TV and make it the
home screen. Follow it top to bottom. Every command runs on the computer, against the TV over
adb.

## Ground rules

- **Ask before you change the TV.** Installing the APK, changing the default home, enabling a
  package and enabling an accessibility service are all changes to the user's device. Say what
  you are about to do and get a yes. Reading state (`get`, `query`, `dumpsys`) needs no approval.
- **Record what was there before** (step 4) so every change can be undone (step 9).
- **Never disable or uninstall the TV's own packages** to make this work. Nothing here needs it.
- **Append, never overwrite,** `enabled_accessibility_services`: the user may already rely on
  other accessibility services.
- **Verify by pressing Home** after every change that affects it, and read the result back. Do not
  report success from a command's `Success` line alone.

Throughout, `T` is the device serial, for example `192.168.1.100:5555`:

```bash
T=192.168.1.100:5555
```

## 1. Connect to the TV

The user turns on debugging on the TV first. Tell them how, in their language:

- Settings › System (or Device Preferences) › About, press **Build** seven times to unlock
  Developer options.
- Developer options: turn on **USB debugging**, and **Network debugging** if it is listed.
- Find the TV's IP address: Settings › Network & Internet › the active connection.

Then:

```bash
adb connect $T
adb devices -l
```

- `connection refused` on port 5555: network debugging is still off, or the TV uses Android 11+
  **Wireless debugging** with a pairing code. Ask the user for the pairing `IP:port` and the
  six-digit code, then `adb pair <ip:port> <code>` and `adb connect` to the address shown under
  Wireless debugging. `adb mdns services` lists TVs that are waiting to pair.
- `unauthorized`: the TV is showing an "Allow USB debugging?" dialog. Ask the user to accept it
  (and tick "Always allow from this computer"), then wait: poll `adb -s $T get-state` until it
  prints `device`, up to about a minute.

Record the model and Android version for later:

```bash
adb -s $T shell getprop ro.product.model
adb -s $T shell getprop ro.build.version.release
```

## 2. Get the APK

Use one of these, in order of preference:

1. The APK the user points you to.
2. The one GitHub Pages publishes next to the preview: `<pages-url>/vhs-launcher.apk`.
3. Build it from this repository (needs JDK 17 and the Android SDK, see README.md):

   ```bash
   ./gradlew assembleRelease
   # app/build/outputs/apk/release/app-release.apk
   ```

## 3. Install

```bash
adb -s $T install -r vhs-launcher.apk
```

`INSTALL_FAILED_UPDATE_INCOMPATIBLE` means an older copy was signed with a different key
(every CI build uses a throwaway key). Ask before `adb -s $T uninstall hu.retro.vhslauncher`:
it clears the user's shelf order and settings.

## 4. Look at the current home setup and write it down

```bash
adb -s $T shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb -s $T shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb -s $T shell pm list packages -d
adb -s $T shell settings get secure enabled_accessibility_services
```

Keep these four outputs; they are the rollback record. Note in particular:

- **The current default home** (the last line of `resolve-activity`).
- **Whether `com.google.android.apps.tv.launcherx` exists and whether it is disabled**
  (it shows in `pm list packages -d`). This is the Google TV home. It also owns the side panel
  (profile, quick settings, notifications) that opens when Home is **held**.
- **The accessibility services already enabled** (`null` means none).

## 5. Pick the path

| What step 4 showed | Path |
| --- | --- |
| No `launcherx` package (plain Android TV, other vendors' homes) | **A** |
| `launcherx` installed and enabled (stock Google TV) | **B** |
| `launcherx` installed but disabled (a third-party home such as Cosy Home replaced it) | **C** |

### Path A: plain Android TV

```bash
adb -s $T shell cmd package set-home-activity hu.retro.vhslauncher/.MainActivity
```

Go to step 7.

### Path B: Google TV with its home enabled

The Google TV home is a system app with a higher intent-filter priority than any other home, so
`set-home-activity` alone does not stick: Home keeps opening Google TV. Its component cannot be
disabled from adb either (`Shell cannot change component state`). VHS Launcher includes a small
accessibility service for this, **VHS Home button**:

- a short press of Home opens VHS Launcher;
- holding Home opens the Google TV side panel, through the system's own "expand notifications"
  path;
- if the Google TV home screen comes up anyway (Back out of an app, after boot), it switches to
  VHS Launcher. That can show Google TV for a moment.

It only filters the Home key and listens for Google TV's home window; it cannot read screen
content (`canRetrieveWindowContent="false"`).

Explain this to the user and ask before enabling it. Then set the default home (harmless, and
right if Google TV is ever removed) and go to step 6:

```bash
adb -s $T shell cmd package set-home-activity hu.retro.vhslauncher/.MainActivity
```

### Path C: Google TV with its home disabled

Home works with a plain `set-home-activity`, but **holding Home does nothing**: the system asks
the Google TV home for its panel and finds it disabled. The log shows
`TvNotificationPanel: ... Could not resolve activityInfo for intent android.app.action.TOGGLE_NOTIFICATION_HANDLER_PANEL`.
No other app can take over that panel.

Ask the user which they want:

- **Keep it as it is:** run the Path A command, and tell them holding Home stays dead.
- **Bring the panel back** (what this project's author chose): re-enable the Google TV home,
  then do Path B.

  ```bash
  adb -s $T shell pm enable com.google.android.apps.tv.launcherx
  adb -s $T shell cmd package set-home-activity hu.retro.vhslauncher/.MainActivity
  ```

  Re-enabling a disabled package changes a decision the user (or their previous home app) made;
  name the package and say why before running it.

## 6. Enable the VHS Home button service (paths B and C-with-panel)

Append to what is already there. With nothing enabled before (`null` or empty):

```bash
adb -s $T shell settings put secure enabled_accessibility_services hu.retro.vhslauncher/hu.retro.vhslauncher.HomeButtonService
adb -s $T shell settings put secure accessibility_enabled 1
```

With other services already enabled, keep them and add ours after a colon:

```bash
CURRENT=$(adb -s $T shell settings get secure enabled_accessibility_services | tr -d '\r')
adb -s $T shell settings put secure enabled_accessibility_services "$CURRENT:hu.retro.vhslauncher/hu.retro.vhslauncher.HomeButtonService"
adb -s $T shell settings put secure accessibility_enabled 1
```

Check it is bound:

```bash
adb -s $T shell dumpsys accessibility | grep -E "Bound services|Enabled services"
```

`Bound services` must list `VHS Home button`.

## 7. Verify

Open something else, press Home, and see what is in front:

```bash
adb -s $T shell am start -n com.android.tv.settings/.MainSettings
adb -s $T shell input keyevent KEYCODE_HOME
sleep 3
adb -s $T shell dumpsys activity activities | grep -m1 topResumedActivity
```

Expected: `hu.retro.vhslauncher/.MainActivity`.

For paths B and C-with-panel, also check holding Home:

```bash
adb -s $T shell input keyevent --longpress KEYCODE_HOME
sleep 2
adb -s $T shell dumpsys activity activities | grep -m1 topResumedActivity
adb -s $T shell input keyevent KEYCODE_BACK
```

Expected: `com.google.android.apps.tv.launcherx/.dashboard.DashboardHandler`.

A screenshot confirms what the user sees:

```bash
adb -s $T exec-out screencap -p > tv.png
```

If Home opens Google TV after the service was enabled, see step 8.

## 8. After every update of the APK

After `adb install -r` of a new version, Android rebinds the accessibility service, but it stops
receiving the Home key: Home opens Google TV again even though `dumpsys accessibility` still
lists the service as bound. Switch it off and on, keeping any other services (put back the
value step 6 left; with ours as the only one):

```bash
adb -s $T shell settings put secure enabled_accessibility_services '""'
adb -s $T shell settings put secure enabled_accessibility_services hu.retro.vhslauncher/hu.retro.vhslauncher.HomeButtonService
adb -s $T shell settings put secure accessibility_enabled 1
```

Then repeat step 7.

## 9. Roll back

Undo only what you changed, using the record from step 4:

```bash
# Previous default home, e.g. Cosy Home or the Google TV home
adb -s $T shell cmd package set-home-activity <previous/home.Activity>

# Accessibility services exactly as they were (use '""' if it was null)
adb -s $T shell settings put secure enabled_accessibility_services "<previous value>"

# Only if you re-enabled the Google TV home in path C
adb -s $T shell pm disable-user --user 0 com.google.android.apps.tv.launcherx

# Only if the user wants the app gone
adb -s $T uninstall hu.retro.vhslauncher
```

Finally, remind the user they can turn network debugging off again in Developer options.

## What to tell the user at the end

- What is now the home screen, and what holding Home does.
- What you changed on the TV (default home, any package enabled, accessibility service), and
  the rollback commands.
- That network debugging is still on, if it is.
