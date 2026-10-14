# Focus

An Android app that shows a full-screen pause with a quote about focus after you've used your phone, or apps you choose, for too long without a break.

## How it works

- **MonitorService** runs in the background and checks every 5 seconds which app is on screen, using Android's Usage Access. Its notification shows live progress, for example "YouTube · 8 of 15 min".
- After *N minutes* of continuous use it drops a heads-up notification from the top of the screen and draws **FocusOverlay** on top of the current app: a frosted-glass pause with a breathing ring and a random quote. Music and video (YouTube, Spotify…) pause while it's up.
- The overlay is a window, not an Activity. Android 10+ and many phone makers block background services from opening activities, but an overlay only needs "Display over other apps".
- If "Display over other apps" is off, or the phone refuses the overlay, the heads-up notification is the reminder on its own. Expanded, it shows the quote and an **I'm done for now** button.
- **Random nudges** (optional): a pop-up notification with a short mindful prompt at unpredictable moments, around every *N* minutes (anywhere from half to one and a half times that). They only come while the phone is unlocked and in use.
- The user waits a short pause (10 s by default) before **Continue anyway** unlocks. **I'm done for now** goes to the home screen.
- Turning the screen off, or switching to an app you aren't watching, resets the timer.
- If you don't pick any apps, it counts all phone usage. Time on the lock screen never counts.
- The main screen shows today's stats: how many focus pauses you got and how often you chose to put the phone down.

## Run it

1. Install [Android Studio](https://developer.android.com/studio) and open this folder.
2. Let Gradle sync. Android Studio downloads the Gradle and SDK versions it needs.
3. Run on a real phone or an emulator (Android 8.0 or newer).
4. In the app, allow **Usage access** and **Notifications**, plus **Display over other apps** for the full-screen pause, set your limit, then turn on **Focus reminders**. Optionally tap **Run in background** and choose "Don't optimize".

### From the command line

Gradle 8.7 needs JDK 17 or 21. It won't run on JDK 25, which recent Android Studio versions bundle. Point `JAVA_HOME` at a supported JDK, for example the JBR 21 that Android Studio downloads into `~/.jdks`:

```sh
export JAVA_HOME=~/.jdks/jbr-21.0.11
./gradlew assembleDebug          # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # install on a connected phone
```

Tip: set the limit to `1` minute to test it quickly, or tap **Preview focus screen**. The ongoing notification shows whether the app you're in is being counted.

## Customize

- Quotes and nudge prompts: `app/src/main/res/values/quotes.xml`
- Colors and glass layers: `app/src/main/res/values/colors.xml`
- Check interval: `CHECK_INTERVAL_MS` in `MonitorService.kt`

## Notes

- Some phone makers (Xiaomi, Oppo, Vivo, Samsung and others) stop background apps aggressively. You may need to allow "Autostart" or turn off battery optimization for Focus.
- This app is Android only. iOS doesn't let apps detect other apps or draw over them.
