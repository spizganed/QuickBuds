package com.spizganed.quickbuds.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.IntentCompat

class KeepAliveReceiver : BroadcastReceiver() {

    private val TARGET_MAC = "A8:E6:E8:92:C1:25"

    /** ACL-only fallback: long enough for A2DP/HFP to come up and connect first. */
    private val ACL_FALLBACK_MS = 8_000L

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)

        if (device?.address != TARGET_MAC) return

        if (!BudsService.backgroundAllowed(context)) {
            Log.d("BudsConn", "KeepAlive: $action ignored (background service off)")
            return
        }

        // Connect when the AUDIO link is up (A2DP or HFP connected), not on the bare ACL link:
        // on ACL the buds are not ready for RFCOMM yet, so that connect used to fail and fall
        // back to 5 s retries. ACL still schedules a late connect, in case no audio profile ever
        // comes up (media audio switched off for the buds); it does nothing if already connected.
        when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                Log.d("BudsConn", "KeepAlive: ACL_CONNECTED (fallback connect in ${ACL_FALLBACK_MS}ms)")
                fireForceConnect(context, ACL_FALLBACK_MS)
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                Log.d("BudsConn", "KeepAlive: ACL_DISCONNECTED (no action)")
            }
            "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED",
            "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED" -> {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                Log.d("BudsConn", "KeepAlive: audio profile state=$state ($action)")
                if (state == BluetoothProfile.STATE_CONNECTED) fireForceConnect(context, 0L)
            }
        }
    }

    private fun fireForceConnect(context: Context, delayMs: Long) {
        val serviceIntent = Intent(context, BudsService::class.java)
        serviceIntent.action = BudsService.ACTION_FORCE_CONNECT
        serviceIntent.putExtra(BudsService.EXTRA_DELAY_MS, delayMs)
        context.startService(serviceIntent)
    }
}