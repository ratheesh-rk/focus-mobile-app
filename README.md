# Focus

An Android app that shows a full-screen pause with a quote about focus after you've used your phone, or apps you choose, for too long without a break.

## How it works

- **MonitorService** runs in the background and checks every 5 seconds which app is on screen, using Android's Usage Access.
- After *N minutes* of continuous use, it opens **FocusActivity** on top of the current app with a random quote.
- The user waits a short pause (10 s by default) before **Continue anyway** unlocks. **I'm done for now** goes to the home screen.
- Turning the screen off, or switching to an app you aren't watching, resets the timer.
- If you don't pick any apps, it counts all phone usage. Time on the lock screen never counts.
- The main screen shows today's stats: how many focus pauses you got and how often you chose to put the phone down.

## Run it

1. Install [Android Studio](https://developer.android.com/studio) and open this folder.
2. Let Gradle sync. Android Studio downloads the Gradle and SDK versions it needs.
3. Run on a real phone or an emulator (Android 8.0 or newer).
4. In the app, grant **Usage Access** and **Display over other apps**, set your limit, then turn on **Focus reminders**. Optionally tap **Keep running in the background** and choose "Don't optimize".

### From the command line

Gradle 8.7 needs JDK 17 or 21. It won't run on JDK 25, which recent Android Studio versions bundle. Point `JAVA_HOME` at a supported JDK, for example the JBR 21 that Android Studio downloads into `~/.jdks`:

```sh
export JAVA_HOME=~/.jdks/jbr-21.0.11
./gradlew assembleDebug          # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # install on a connected phone
```

Tip: set the limit to `1` minute to test it quickly, or tap **Preview focus screen**.

## Customize

- Quotes: `app/src/main/res/values/quotes.xml`
- Colors: `app/src/main/res/values/colors.xml`
- Check interval: `CHECK_INTERVAL_MS` in `MonitorService.kt`

## Notes

- Some phone makers (Xiaomi, Oppo, Vivo, Samsung and others) stop background apps aggressively. You may need to allow "Autostart" or turn off battery optimization for Focus.
- This app is Android only. iOS doesn't let apps detect other apps or draw over them.
