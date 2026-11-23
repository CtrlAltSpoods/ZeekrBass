/*
 * ZeekrBass — https://github.com/CtrlAltSpoods/ZeekrBass
 *
 * Unofficial community project. NOT affiliated with, endorsed by, sponsored by or otherwise
 * connected to Zeekr, Zeekr Intelligent Technology, Geely or any of their affiliates.
 * "Zeekr" and "7X" are trademarks of their respective owners and are used here only to
 * describe the vehicle this app was made for.
 *
 * Experimental and still in testing. Provided "as is", without warranty of any kind.
 * Installing third-party apps on your vehicle is entirely at your own risk.
 */

package com.ctrlaltspoods.zeekrbass;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Restores the last mode (and the floating panel) after a reboot or an app update. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }
        Prefs prefs = new Prefs(context);
        if (!prefs.restoreOnBoot()) return;
        if (prefs.mode() == Mode.OFF && !prefs.overlayVisible() && !EqConfig.get(context).enabled) {
            return;
        }
        try {
            ZeekrBassService.send(context,
                    ZeekrBassService.intent(context, ZeekrBassService.ACTION_RESTORE));
        } catch (RuntimeException e) {
            Log.w("ZeekrBass", "Could not restore after " + action, e);
        }
    }
}
