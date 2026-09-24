# Miir Presenter — cloud APK build

This project includes a GitHub Actions workflow that builds an installable Android debug APK.

## Fast path
1. Create a new empty GitHub repository.
2. Upload the **contents of this ZIP** to the repository root (not the ZIP itself).
3. Open **Actions** → **Build Miir Presenter APK**.
4. Press **Run workflow** → **Run workflow**.
5. When the build finishes, open the run and download the artifact **MiirPresenter-v0.13-debug-apk**.
6. Unzip that artifact. Inside is `MiirPresenter-v0.13-debug.apk`.
7. Transfer the APK to the Android device and install it. Android may ask to allow installation from that source.

The workflow uses Java 17, Gradle 8.9, Android Gradle Plugin 8.7.3 and compiles the app with Android API 35.

If the first build fails, copy the red error output from the **Build debug APK** step into ChatGPT so the source can be corrected.
