# SecureApp Android

Native Android client workspace for SecureChat.

## Build environment

- Java 17
- Gradle 8.9
- Android SDK 35
- GitHub Actions workflow: `.github/workflows/build-android-apk.yml`

The repository currently contains the Gradle project scaffold and APK build workflow. The native chat client source still needs to be added before the first APK build can succeed.

## Build APK

After the Android source is added:
1. Open **Actions**.
2. Select **Build SecureApp Android APK**.
3. Click **Run workflow**.
4. Download the `SecureApp-Android-debug-APK` artifact after a successful run.

No APK has been built or device-tested yet.
