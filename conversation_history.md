# Conversation History - NB4A (Flux Fork)

This document contains a summary of the fixes and features implemented during the development of the Flux Android application fork.

## 1. Resource Linking Fixes
- **Style Error:** Fixed missing `Widget.MaterialComponents.Chip.Choice.SagerNet` by defining it in `styles.xml`.
- **Layout Error:** Replaced missing `AppearanceFoundation.Title` in `layout_stun.xml` with standard `?attr/textAppearanceHeadline6`.

## 2. Stability and Crashes
- **Notifications:** Removed the "Switch" button from the service notification (`ServiceNotification.kt`) as it was unused and caused crashes in this fork.

## 3. UI and Theme Improvements
- **Pink Elements:** Fixed the "pink bar" issue by changing the background of `StatsBar` to `?android:attr/windowBackground`.
- **System Navigation Bar:** Enabled Edge-to-Edge and set the system navigation bar (area with back/home buttons) to be transparent across all color themes in `themes.xml` and `ThemedActivity.kt`.
- **Status & Feedback:** Simplified main status labels to "ONLINE", "OFFLINE", "WAIT", and "ERROR". Added red color for error states.

## 4. Feature: Connection Monitoring & Feedback
- **Network Awareness:** Added `ConnectivityManager` monitoring to `MainActivity.kt`. The app now correctly displays "NO NETWORK" if Wi-Fi/LTE is disabled, preventing false "Connected" statuses.
- **Speed Display:** Implemented real-time upload/download speed indicators (▲/▼) on the main activity screen, synchronized with the sing-box core.

## 5. Feature: Advanced "White List" Mode (Белые Списки)
- **Automatic Group Management:** Implemented logic to automatically create a subscription group from `https://gitverse.ru/api/repos/zieng2/wl/raw/branch/master/list_universal.txt`.
- **Parallel Proxy Selection:** 
    - Created a "race-condition" search algorithm. 
    - Tests 40+ proxies simultaneously using Coroutines and Channels.
    - Connects to the **first successful result** immediately.
    - Average search time reduced from minutes to ~1-2 seconds.
- **Background Monitoring:** Added a background job that checks the active server every minute. If the server dies, the app automatically finds and connects to the next working one.
- **UI Progress:** Added a `LinearProgressIndicator` and a detailed search status label ("Checked X/40...") at the bottom of the screen.

## 6. Settings & Defaults
- **Preference Fix:** Removed the broken `ColorPickerPreference` from settings to prevent crashes during theme changes.
- **User Choice:** Modified `toggleVpn()` to respect user-defined settings instead of overriding them with hardcoded defaults.

---
*Date of export: May 2024*