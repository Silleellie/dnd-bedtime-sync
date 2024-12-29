# DNDSync

This app was developed to synchronize **Do Not Disturb** (_DND_) and **Bedtime mode** from [Digital Wellbeing](https://play.google.com/store/apps/details?id=com.google.android.apps.wellbeing&hl=en_US) between an Android phone and a Wear OS watch.
DND synchronization is only supported officially if you are using a Samsung phone with your Galaxy watch and Bedtime Mode synchronization is only newly available for the newest Pixel Watch 2, if paired with a Pixel phone, with this app you get **_both_** on any Android phone and watch!

#### Functionalities

- _1-way sync_ or a _2-way sync_ of **DND**, depending on your settings.
- When _DND_ is activated on the phone, depending on your settings, **Bedtime mode** can also be activated to the watch!
  - (Useful for **Xiaomi phones**, which don't have the _Digital Wellbeing_ app)
- Automatically toggle **Bedtime mode** for the watch when it is activated on the phone
  - (At night, when I charge my phone, Bedtime Mode on my phone is automatically enabled, and I wanted to sync and enable the same mode on the watch)
- Automatically toggle **Power Saver** mode in combo with **Bedtime Mode** on the watch whenever Bedtime Mode is synced from the phone

Majority of the credits goes to [@rhaeus](https://github.com/rhaeus) for the initial app and to [@DreadedLama](https://github.com/DreadedLama) for developing a better Bedtime Mode implementation!

#### Confirmed Devices

- Tested on Nothing Phone (1) (_Android 13_) paired with a Galaxy Watch 4 (_40mm_, _Wear OS 4.0_)
- Tested on OnePlus Nord N30 5G (_Android 14_) paired with a Pixel Watch 1 (_Wear OS 5_)
- Tested on an Android emulator (_Android 15_) paired with a Wear OS emulator (_Wear OS 5_)

## Setup

**Manual installation with ADB is required. (don't worry, it's very easy!)**

### Prerequisites

- Download the `.apk` files from the [latest releases](https://github.com/Silleellie/dnd-bedtime-sync/releases/latest)
  - `dndsync-mobile.apk`
  - `dndsync-wear.apk`
- Enable Bedtime Mode notifications for Digital Wellbeing app on your phone (_enabled by default_)
  - This app knows if Bedtime Mode is activated when it's notification pops up (since there's no public API for the _Digital Wellbeing_ app)
- If you don't have ADB, you can download a lightweight version from the [github release page](https://github.com/K3V1991/ADB-and-FastbootPlusPlus/releases) of _ADB and Fastboot++_
  - In the following instructions, version **1.0.8** is used

### Phone

<p float="left">
  <img src="/images/mobile.png" width="300" />
</p>

1. Install `dndsync-mobile.apk` on the phone via ADB
   - Enable `USB Debugging` in the _Developer Options_ of your phone and the connect it via USB to your computer
   - Run `adb install dndsync-mobile.apk`
2. Disconnect the phone from the PC
   - Disable `USB Debugging` from the _Developer Options_ of your phone
3. Find and open "DND Sync" and grant the permission for _DND Access_ and _Bedtime Access_ by clicking on _"Notification Permission"_ located at the bottom. This will open the permission screen.
   - This permission is required so that the app can _read and write_ DND state and _read_ Bedtime Mode. Without this permission, the sync will fail.
4. Go back on the app and check that `Notification Permission` now says **Notification access granted** (_you may need to tap on it to update_)

### Watch

<img src="/images/wear.png" width="200" />

Setting up the watch is a bit more _tricky_ since Wear OS lacks the permission screen for DND access, but the permission needed can be **easily granted via ADB**!

> [!NOTE]
> This has only been tested on the [Confirmed Devices](#confirmed-devices) and it might not work on other devices!

1. Connect the watch to your computer via **ADB** (watch and computer have to be in the **same network**)
   - Enable Developer Options: Go to `Settings -> About watch -> Software -> tap "Software version" 5 times`, now developer mode is on
   - Enable `ADB debugging` and `Debug over WIFI` (in `Settings -> Developer Options`)
   - Click on `Pair new device`
   - Note the watch IP address and port, something like `192.168.0.100:5555`
   - Note also the pair key, something like `123456`
   - Pair the watch with `adb pair 192.168.0.100:5555 123456` (**_insert your value!_**)
   - Check that now your PC is listed under `Paired devices` and there's a text under it saying `Currently connected`
     - If not, perform `adb connect 192.168.0.100:6666` with the IP address and port listed in the `Debug over WIFI` screen
2. Install the app `dndsync-wear.apk` on the watch via ADB
   - Run `adb install dndsync-wear.apk`
3. Grant permission for **DND access** (_This allows the app to listen to DND changes and to change the DND setting_)
   - Run `adb shell cmd notification allow_listener it.silleellie.dndsync/it.silleellie.dndsync.DNDNotificationService`
4. Grant permission for **Secure Setting access** (_This allows the app to change BedTime mode setting on the watch_)
   - Run `adb shell pm grant it.silleellie.dndsync android.permission.WRITE_SECURE_SETTINGS`
5. Open the app on the watch, scroll to the permission section and check if both `DND Permission`
   and `Secure Settings Permission` say **_Granted_** (_you may need to tap on the menu entries for them to update_)

> [!IMPORTANT]
> Disable `ADB debugging` and `Debug over WIFI`, because these options drain the battery!

## Settings

### Phone preferences

- With the **_Sync DND state to watch_** switch you can enable and disable the sync for _DND_ mode.
  If enabled, a _DND_ change on the phone will lead to _DND_ change on the watch.
- With the **_Enable Bedtime mode on DND sync_** switch, you can choose to activate the bedtime mode on the watch when DND is activated on the phone. Useful for all those phones missing the _Digital wellbeing_ app
- With the **_Sync Bedtime mode to watch_** switch you can enable and disable the sync for bedtime mode.
  If enabled, when _Bedtime mode_ is _enabled/disabled/paused_ on the phone, it will be _enabled/disabled/paused_ on the watch
- If you enable the setting **_Enable Power Saver mode with Bedtime_**, the watch will turn on _power save_ mode whenever the _Bedtime Mode_ is synced from the phone, either due to _Sync Bedtime mode to watch_ or to _Enable Bedtime mode on DND sync_.
  - Wear OS (4+) automatically enters a deep sleep disabling all networks and optimizing battery with Bedtime Mode

### Watch preferences

- If you enable the setting **_Sync DND state to phone_**, a DND change on the watch will lead to a DND change on the phone
- If you enable the setting **_Vibrate on sync_**, the watch will vibrate whenever it receives a sync request from the phone

## To do (for developers)

There are only two flags which the _Power saver mode_ of this app does not enable but which are enabled by the _Power saver mode_ of the watch itself:

- Change screen timeout setting to _10 sec_ when _Power Saver Mode_ is enabled, just like it is if enabled via the watch
- (Optional) For coherence, also the _wake up the watch by tilt_ should be disabled via code. This is optional since it is disabled automatically whenever the low power mode is enabled and does not require setting manually the flag to 0, but still...

Pull requests are welcome!

## Note

If you are unable to "Allow Notification Access" to the mobile app and it is faded, go to Settings -> Apps -> Find and open the DND Sync app -> Click the 3 dots on top right and grant "Allow Restricted Settings" access. Now you'll be able to grant the Notification access to mobile app.

> ![WARNING]
> Developer mode is required to grant "Allow Restricted Settings"
