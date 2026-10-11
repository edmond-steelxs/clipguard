# ClipGuard – replacement app for the RECARO Easy-Tech clip (Android)

## How it works
- Connects to the clip (default address C0:BB:3E:B1:5C:1B) and listens to
  characteristic 44ba4000-4cf5-46d8-a887-a846d9de712d: 0x01 = closed, other = open.
- Clip closed + Bluetooth connection lost (you walked away):
  1. Immediate warning notification
  2. After the grace period (default 120 s): loud alarm at full alarm volume + vibration
  3. After the extra time (default 60 s): SMS to the emergency contacts
- Opening the clip, coming back in range, or tapping "Child is out" stops the alarm.
- Battery level is shown; warning below 20 %.

## Build the APK (no software needed on your PC)
1. Create a free account on github.com and a new **private** repository "ClipGuard".
2. On a computer: "Add file" → "Upload files" → drag in all files and folders from this zip
   (including the `.github` folder – enable "show hidden files" if you don't see it).
3. Commit. Open the **Actions** tab: the "Build APK" job runs (about 5 minutes).
4. Open the finished run → download the artifact **ClipGuard-apk** → unzip → `app-debug.apk`.
5. Copy it to the phone and open it (allow "install unknown apps" once).

## First setup on the phone
1. Open ClipGuard, allow Bluetooth ("Nearby devices"), notifications and SMS.
2. Enter emergency contact number(s), e.g. +32499...; Save.
3. Tap "Allow running in background (battery)" and allow it.
4. Close nRF Connect (only one app can be connected to the clip at a time).
5. Tap "Start monitoring".

## Test before relying on it
- Close the clip → status shows "clip closed", raw value 01.
- Open the clip → "clip open". Note the raw value.
- Close the clip, walk away until the connection drops → warning, then alarm.
- Open the clip next to the phone → make sure NO alarm follows.
- "Send test SMS to contacts" once.
