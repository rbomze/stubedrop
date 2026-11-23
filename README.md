# S-Tube Drop

Send a YouTube video from your phone or browser to [SmartTube](https://github.com/yuliskov/SmartTube) on your Android TV, with one tap.

```
 ┌───────────────────-─┐
 │ Phone: share a link │──┐
 │  S-Tube Drop Sender │  │  HTTP(S) POST/GET        ┌──────────────────────────┐
 └──────────────────-──┘  ├────────────────────────▶ │ TV: S-Tube Drop receiver │──▶ SmartTube 
 ┌──────────────────-──┐  │  on your local network   └──────────────────────────┘    plays it
 │ Firefox / LibreWolf │──┘
 │  toolbar button     │
 └───────────────────-─┘
```

Everything stays on your own network. There is no cloud service and no account.

## What's in this repository

| Folder | What it is | Version |
|---|---|---|
| [`android_receiver/`](android_receiver) | **S-Tube Drop**: runs on the TV, listens for links and opens them in SmartTube | 0.10 |
| [`android_sender/`](android_sender) | **S-Tube Drop Sender**: Android phone app; share a YouTube link to it and it goes to the TV | 0.10 |
| [`firefox_extension/`](firefox_extension) | Firefox / LibreWolf extension: a toolbar button that sends the current video | 0.12 |

You need the receiver plus at least one sender. Any other client works too (see [Protocol](#protocol)), for example the [HTTP Shortcuts](https://http-shortcuts.rmy.ch/) app or `curl`.

## Features

- **Receiver:** a foreground service, optionally started at boot. It has a configurable port (default `8765`) and a password (default `changeme`, change it).
- **HTTP and HTTPS on one port.** The TLS certificate is generated on the TV when the app first runs; its private key never leaves the device.
- **POST or GET.** With POST the link goes in the request body, so it needs no URL-escaping, and the password goes in an `X-Token` header, so it never appears in a URL.
- **Only YouTube links are accepted** (`youtube.com`, `youtu.be`, `music.youtube.com`); anything else is refused.
- **Brute-force protection:** 3 wrong passwords within 60 seconds silently block that IP address for 4 minutes.
- **Privacy:** the receiver keeps only the last 5 events, in memory. Nothing is written to disk and it is cleared when the app stops.
- **Test link:** sending `testurl` instead of a link checks the connection, password and certificate without starting a video.
- **Sender app:** a share target for YouTube links, configurable like the extension, with certificate pinning (you confirm the TV's SHA-256 fingerprint once).
- **Extension:** the toolbar button is only active on pages with a playable video; there is also a right-click entry on YouTube links. Results are shown in the popup, with no OS notifications. It asks only for `tabs`, `storage` and `contextMenus`.

## Quick start

### 1. Receiver on the TV

1. Install the receiver APK (see [Building](#building)) on the TV, for example with `adb install`.
2. Open **S-Tube Drop** on the TV. Set a port and a password, and tap **Save & (re)start**.
3. Allow **Display over other apps** for S-Tube Drop (the app has a button for it). Android needs this to let a background app open SmartTube.
4. Check that the **Target app** is your SmartTube build: `app.smarttube.fdroid` (F-Droid, the default) or `com.teamsmart.videomanager.tv` (Play Store).
5. Give the TV a fixed IP address in your router, or the senders stop working when it changes.

The receiver's screen shows its URLs, the certificate fingerprint and the last five drops.

### 2a. Phone: S-Tube Drop Sender

1. Install the sender APK on the phone.
2. Open it and enter the TV's **IP address**, **port** and **password**. Leave **HTTPS** on and **Request method** on POST.
3. Tap **Trust certificate…**, compare the fingerprint with the one shown on the TV, and confirm. This is needed once for each address.
4. Tap **Test connection**; it should say *Connected*.
5. In PipePipe, the YouTube app, a browser or any other app, tap **Share** and choose **S-Tube Drop Sender**.

### 2b. Browser: Firefox / LibreWolf extension

1. Install the extension (see [Installing the extension](#installing-the-extension)).
2. Open its **Preferences** (in `about:addons`) and enter the TV's IP address, port and password. HTTPS is on by default.
3. Click **Trust certificate…**, accept the browser's certificate warning once, come back and click **Test connection**.
4. Open a YouTube video and click the toolbar button.

### 2c. Anything else

Use HTTP Shortcuts, a script, or `curl`: see the protocol below.

## Protocol

The receiver answers on one port, for both `http://` and `https://`.

**POST (recommended):** the link is the body, the password is a header.

```sh
curl -k -X POST https://TV-IP:8765/ \
     -H 'X-Token: changeme' \
     -H 'Content-Type: text/plain' \
     --data 'https://www.youtube.com/watch?v=VIDEO_ID&t=30s'
```

A form body (`url=<escaped link>`, `application/x-www-form-urlencoded`) is also accepted, with the password in the `X-Token` header.

**GET:** everything is in the query string, so the link must be URL-escaped.

```sh
curl -k 'https://TV-IP:8765/?url=https%3A%2F%2Fyoutu.be%2FVIDEO_ID&t=changeme'
```

**Responses**

| Status | Body | Meaning |
|---|---|---|
| `200` | `ok` | The link was handed to SmartTube |
| `200` | HTML page | The `testurl` check succeeded |
| `403` | `bad password` | Wrong password (counts towards the block) |
| `403` | `bad url` | Not a YouTube link (does not count towards the block) |
| `500` | `launch failed` | SmartTube could not be started; check the target package and the overlay permission |

`-k` is needed because the certificate is self-signed. Use plain `http://` to skip TLS entirely, at the cost of sending the password in clear text on your network.

## Security notes

- The password is a shared secret, not user accounts. Change `changeme`.
- Use HTTPS if anyone else is on your network. The certificate is self-signed, so trust is established by comparing its SHA-256 fingerprint once: the sender app pins it, and the extension relies on the browser's certificate exception.
- If you reinstall the receiver after clearing its data, it makes a new certificate. The senders will warn that the certificate changed; trust it again.
- Do not expose the port to the internet.

## Building

Requirements: **JDK 21** (newer JDKs are too new for this Gradle version), **Gradle 8.14** and the Android SDK (compile SDK 35). The repository has no Gradle wrapper.

Both apps are signed with the same keystore, which is deliberately not in the repository. Create your own:

```sh
keytool -genkeypair -v -keystore android_receiver/stubedrop.jks -alias stubedrop \
        -keyalg RSA -keysize 2048 -validity 10000
```

Then create `android_receiver/keystore.properties` and `android_sender/keystore.properties` (both are git-ignored):

```properties
storeFile=stubedrop.jks                      # in android_sender: ../android_receiver/stubedrop.jks
storePassword=…
keyAlias=stubedrop
keyPassword=…
```

Point each app at the Android SDK with `local.properties` (`sdk.dir=/path/to/Android/sdk`), then build:

```sh
cd android_receiver && gradle assembleRelease    # app/build/outputs/apk/release/app-release.apk
cd ../android_sender && gradle assembleRelease
```

Keep the keystore safe: an app can only be updated in place if it is signed with the same key.

### Installing over adb

```sh
adb connect TV-IP:PORT          # wireless debugging; the port changes, check the TV's developer options
adb install -r android_receiver/app/build/outputs/apk/release/app-release.apk
```

## Installing the extension

The extension is plain JavaScript: there is nothing to build, only to package.

```sh
cd firefox_extension && zip -r ../stubedrop-firefox.xpi . -x '.*'
```

Release Firefox and LibreWolf only keep extensions that Mozilla has signed:

- **Try it:** open `about:debugging#/runtime/this-firefox` → **Load Temporary Add-on…** and pick `firefox_extension/manifest.json`. It disappears when the browser restarts.
- **Keep it:** sign it as an *unlisted* add-on on [addons.mozilla.org](https://addons.mozilla.org/developers/) (free, automated), download the signed `.xpi` and install it from `about:addons` → gear icon → **Install Add-on From File…**. Raise `version` in `manifest.json` for every new upload.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Sender or extension says *not reachable* | The TV is off, the receiver isn't running (open it once after an update), the IP or port is wrong, or the phone is on a different network |
| *The TV rejected the password* | The password differs from the one in the receiver app. After 3 attempts that IP is blocked for 4 minutes |
| *Certificate not trusted yet* / *changed* | Tap **Trust certificate…** again. This is needed once per address, and again if the receiver's certificate was regenerated |
| The video doesn't start, but the TV answers `ok` | The **Display over other apps** permission is missing, or the target package doesn't match your SmartTube build |
| `launch failed` in the receiver log | SmartTube isn't installed under the package name set in **Target app** |
| The extension's button is grey | The current tab isn't a YouTube video, playlist, short or live page |

## Project layout

```
android_receiver/   Java, no AndroidX: foreground service, raw HTTP server, TLS via SSLEngine
android_sender/     Java, no AndroidX: share target, settings screen, certificate pinning
firefox_extension/  Manifest V3: background event page, popup, preferences page
```

Both Android apps build their screens in code and have no dependencies beyond the Android SDK.

## Licence

No licence has been chosen yet, so all rights are reserved by default. Add a `LICENSE` file to allow others to use or modify the code.
