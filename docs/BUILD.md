# Building Intent

A step-by-step guide to building and running the app. Written for **Windows + a physical
Android phone** (the main dev setup); macOS/Linux differences are noted where they matter.

| You want to… | Command | Needs |
|---|---|---|
| Run on your phone while developing | `npm run android:device` | Phone by USB (or Wi-Fi debugging), Metro |
| Build an APK you can install without a cable or Metro | `npm run build:apk` | — |
| Build for Google Play | `npm run build:aab` | Your own upload key (see "Release signing") |
| Check before you push | `npm run check` | — |

---

## 1. One-time setup

### 1.1 Install the tools

| Tool | Version | Windows install |
|---|---|---|
| Node.js | 22.11+ | `winget install OpenJS.NodeJS.LTS` |
| JDK | **17** (Temurin) | `winget install EclipseAdoptium.Temurin.17.JDK` |
| Android Studio | latest | `winget install Google.AndroidStudio` |
| Git | any | `winget install Git.Git` |

### 1.2 Android SDK packages

Android Studio → **More Actions → SDK Manager**:

- **SDK Platforms:** Android API **37** (the project compiles against 37 and targets 36).
- **SDK Tools** (tick *Show Package Details*):
  - Android SDK Build-Tools **37.0.0**
  - Android SDK **Platform-Tools** (keep it updated: old `adb` breaks Wi-Fi pairing)
  - NDK **27.1.12297006**
  - CMake (any 3.22+)
  - Android SDK Command-line Tools
  - Google USB Driver (Windows, for Pixel and many other phones)

Gradle can download missing pieces itself once the licenses are accepted, but installing them
here avoids a slow first build.

### 1.3 Environment variables (Windows)

Run in **PowerShell**. It finds the real JDK folder, so don't type the path by hand:

```powershell
$jdk = Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory |
  Where-Object Name -like "jdk-17*" | Select-Object -First 1 -ExpandProperty FullName
[Environment]::SetEnvironmentVariable("JAVA_HOME", $jdk, "User")
[Environment]::SetEnvironmentVariable("ANDROID_HOME", "$env:LOCALAPPDATA\Android\Sdk", "User")
$p = [Environment]::GetEnvironmentVariable("Path", "User")
[Environment]::SetEnvironmentVariable("Path", "$p;$env:LOCALAPPDATA\Android\Sdk\platform-tools;$env:LOCALAPPDATA\Android\Sdk\emulator", "User")
```

Don't use `setx PATH`: it truncates PATH at 1024 characters.

**Close every terminal (VS Code too)**, open a new one, and check:

```cmd
echo %JAVA_HOME%
"%JAVA_HOME%\bin\java" -version
where adb
adb version
npx react-native doctor
```

- `java -version` should print 17.x.
- `where adb` should print the SDK copy **first**:
  `C:\Users\<you>\AppData\Local\Android\Sdk\platform-tools\adb.exe`
- If `echo %JAVA_HOME%` shows a wrong or old path, a **system-level** `JAVA_HOME` is overriding
  yours. Fix or remove it in *Edit the system environment variables → System variables*.

macOS/Linux: set `JAVA_HOME` and `ANDROID_HOME` in `~/.zshrc` / `~/.bashrc`, and add
`$ANDROID_HOME/platform-tools` to `PATH`.

### 1.4 Prepare your phone

1. **Developer options:** Settings → About phone → tap **Build number** (on vivo: **Software
   version**) 7 times.
2. **USB debugging:** Developer options → **USB debugging** on.
3. **Brand-specific extras:**
   - **vivo / iQOO:** turn off *Verify apps over USB*; turn on *Install via USB* if present. During
     install, watch for vivo's own "Install via USB?" prompt (it may ask for the vivo account
     password). Missing it gives `INSTALL_FAILED_USER_RESTRICTED`; just run again.
   - **Xiaomi / Redmi / POCO:** turn on *Install via USB* and *USB debugging (Security
     settings)*. Needs a SIM and a Mi account.
   - **Oppo / Realme:** turn on *Disable permission monitoring* if present.
4. **Plug in with a data cable** and set USB mode to **File transfer**. Accept *Allow USB
   debugging?* and tick **Always allow**.
5. **Check:** `adb devices` should show `XXXX  device`.

| `adb devices` shows | Fix |
|---|---|
| nothing | Another cable/port. Check Windows Device Manager for a yellow ⚠ and install the Google USB Driver (or the phone maker's driver). |
| `unauthorized` | Unlock the phone and accept the prompt. Otherwise Developer options → *Revoke USB debugging authorizations*, then replug. |
| `offline` | `adb kill-server`, then replug. |

**Wi-Fi instead of USB** (Android 11+, same network): Developer options → *Wireless debugging* →
*Pair device with pairing code*, and keep that popup open.
- Pair: `adb pair IP:PAIRING_PORT` with the code from the popup.
- Connect: `adb connect IP:PORT` with the port from the **main** Wireless debugging screen. It's a
  different port.
- Router isolation, VPNs, or a Windows *Public* network profile can block this; `ping` the phone
  to check.

---

## 2. Get the code

```cmd
git clone https://github.com/kandalaguruprasad/intent-checkpoint-android.git
cd intent-checkpoint-android
git checkout claude/compassionate-turing-nhkqoe
npm install
```

After pulling new changes, always run `npm install` again: native modules may have changed.

---

## 3. Run on your phone (development)

```cmd
npm run android:device
```

- Builds only for your phone's CPU (`--active-arch-only`), installs, and starts **Metro** in a
  second window. Keep that window open: the debug app loads its JavaScript from it.
- First build: 10–30 min (it compiles native C++). Later builds take minutes.
- JS/TS changes reload instantly. **Kotlin or manifest changes need the command re-run.**
- To reload JS, shake the phone for the dev menu, or press `r` in the Metro window.

**Phone and PC on different networks / Metro can't be reached:**

```cmd
adb reverse tcp:8081 tcp:8081
```

### First launch on the phone

1. **Choose Apps:** pick one or two apps.
2. **Permissions:** turn on **Usage Access** and **Display over other apps**, allow notifications.
3. **vivo battery settings:** allow *Background power consumption / High background power usage*
   and *Auto-launch* for Intent, and lock it in Recents. Without this, vivo stops the service in
   the background.
4. Open one of your apps: the checkpoint should appear within about 1–2 s.

To see the overlays without opening another app: **Settings → Preview**.

---

## 4. Build an installable APK (no cable, no Metro)

```cmd
npm run build:apk
```

Output: `android\app\build\outputs\apk\release\app-release.apk` (all CPU types, JS bundled in).

- **Install it:** `adb install -r android\app\build\outputs\apk\release\app-release.apk`, or copy
  the file to the phone and open it (allow "install unknown apps" for your file manager).
- **Smaller APK for one phone:** most phones since 2019 are arm64.
  ```cmd
  npx react-native build-android --tasks assembleRelease --extra-params "-PreactNativeArchitectures=arm64-v8a"
  ```
- Without an upload key configured, this APK is signed with the **debug key**. Fine for your own
  phone and testers; **not** for Play.
- **Signature mismatch:** a phone that has the debug build installed must uninstall it before
  installing a build signed with a different key (and the reverse). Uninstalling wipes local data:
  `adb uninstall dev.intent.checkpoint`.

---

## 5. Release signing (before sharing widely or uploading to Play)

**Create an upload key once, keep it outside the repo, and back it up.** Lose it and you can't
update the app:

```cmd
keytool -genkeypair -v -storetype PKCS12 -keystore %USERPROFILE%\keys\intent-upload.p12 ^
  -alias intent-upload -keyalg RSA -keysize 2048 -validity 10000
```

**Add the key's details to your user-level `%USERPROFILE%\.gradle\gradle.properties`** (not the
project's):

```properties
INTENT_UPLOAD_STORE_FILE=C:/Users/<you>/keys/intent-upload.p12
INTENT_UPLOAD_STORE_PASSWORD=********
INTENT_UPLOAD_KEY_ALIAS=intent-upload
INTENT_UPLOAD_KEY_PASSWORD=********
```

`android/app/build.gradle` picks these up automatically; with them present, `build:apk` and
`build:aab` are signed with your key.

**For Google Play:**

```cmd
npm run build:aab
```

Output: `android\app\build\outputs\bundle\release\app-release.aab`. Enrol in Play App Signing
when you upload. Before submitting, go through the Play checklist in the PRD (§62): Data Safety,
`specialUse` justification, store copy.

---

## 6. Checks and tests

```cmd
npm run check
```
Runs lint, TypeScript and Jest.

```cmd
cd android && gradlew -p intent-core test
```
Runs the engine state machine on the JVM (no phone needed). On macOS/Linux use `./gradlew`.

CI (`.github/workflows/ci.yml`) runs all of this on every push, builds the debug APK, and runs
the **emulator job**: real build, overlay flow driven by UI Automator, screenshots uploaded as
the `emulator-screenshots` artifact.

---

## 7. Troubleshooting

| Error | Cause → fix |
|---|---|
| `'adb' is not recognized` | Platform-tools not on PATH → step 1.3, then open a **new** terminal. |
| `JAVA_HOME is not set` / `set to an invalid directory` | Wrong or placeholder path → run the 1.3 PowerShell block (it finds the real folder). |
| `No connected devices!` | Build succeeded; there's no phone → section 1.4, `adb devices`. |
| `INSTALL_FAILED_USER_RESTRICTED` | vivo/Xiaomi install prompt missed or USB install disabled → 1.4 step 3, then retry. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Installed app signed with another key → `adb uninstall dev.intent.checkpoint` (wipes data). |
| `adb pair` → `protocol fault` | Old adb earlier on PATH, or a stale pairing code → `where adb`, update Platform-Tools, reopen the pairing popup. |
| `adb connect` → `10060` | Phone unreachable on the network → `ping` it; VPN / router isolation / Public network profile. |
| Red screen "Unable to load script" | Debug build can't reach Metro → keep Metro running, `adb reverse tcp:8081 tcp:8081`, or install the release APK instead. |
| First build very slow | Normal (native C++ for every CPU type). `android:device` builds only your phone's. |
| Checkpoint never appears | Usage Access or Display over other apps is off → Settings tab → Permission health. |
| Works, then stops after a while in the background | OEM battery killer → section 3, "vivo battery settings". |
| Gradle cache weirdness after pulling | `cd android && gradlew clean`, then build again. |
