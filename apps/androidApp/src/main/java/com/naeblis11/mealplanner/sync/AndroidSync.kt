package com.naeblis11.mealplanner.sync

import android.content.Context
import android.os.Build
import com.naeblis11.mealplanner.app.AppContainer
import java.io.File

/**
 * The phone's LAN sync, made once per process (MealPlannerApplication). It uses:
 * - the pairing sealed in filesDir (KeystorePairingStore, under the AndroidKeyStore key);
 * - mDNS through NsdManager;
 * - the Wi-Fi check;
 * - the device name from Build.MODEL.
 * Making it touches neither the Keystore nor the network: the pairing is read off the main thread by
 * PhoneSyncControls, and a phone that never paired has no file to open.
 */
object AndroidSync {
    fun create(context: Context, container: AppContainer): PhoneSyncControls {
        val app = context.applicationContext
        val engine = PhoneSync(
            db = container.database,
            recipes = container.recipes,
            imagesDir = container.imagesDir,
            store = KeystorePairingStore(File(app.filesDir, KeystorePairingStore.FILE_NAME), AndroidKeyStoreKeys::pairingKey),
            finder = NsdPcFinder(app),
            deviceName = DeviceNames.clean(Build.MODEL),
        )
        val wifi = AndroidWifiCheck(app)
        return PhoneSyncControls(engine, container.recipes, container.imagesDir, wifi = wifi::onWifi)
    }
}
