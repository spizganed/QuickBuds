package com.spizganed.quickbuds.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import com.spizganed.quickbuds.protocol.ModelCatalog
import com.spizganed.quickbuds.protocol.OpoProtocol
import com.spizganed.quickbuds.ui.ThemeRes

/**
 * Which paired device is "the buds": the paired device named like the model picked by hand, else the address saved last (the buds
 * whose audio connected most recently, see [KeepAliveReceiver]), else the first bonded device that
 * offers one of the OPPO SPP UUIDs or carries a model name from `models.json`.
 */
@SuppressLint("MissingPermission")
object BudsDevice {

    private const val KEY_ADDRESS = "budsAddress"
    private val SPP_UUIDS = setOf(OpoProtocol.SPP_UUID_PRIMARY, OpoProtocol.SPP_UUID_FALLBACK)

    fun isBuds(context: Context, device: BluetoothDevice): Boolean = runCatching {
        device.uuids?.any { it.uuid.toString().uppercase() in SPP_UUIDS } == true ||
            ModelCatalog.all(context).any { it.name == device.name }
    }.getOrDefault(false)

    fun remember(context: Context, device: BluetoothDevice) {
        prefs(context).edit().putString(KEY_ADDRESS, device.address).apply()
    }

    // ponytail: with several matching buds and none saved yet, the first bonded match wins; add a
    // picker if users report it (the audio-connect broadcast normally saves the right one first).
    fun find(context: Context): BluetoothDevice? {
        val bonded = runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices
        }.getOrNull().orEmpty()
        val saved = prefs(context).getString(KEY_ADDRESS, null)
        // A model picked by hand names the buds the person means, whichever buds connected for audio last.
        val picked = ModelCatalog.manual(context)?.let { id -> ModelCatalog.all(context).firstOrNull { it.id == id }?.name }
        picked?.let { name -> bonded.firstOrNull { it.name == name } }?.let { return it.also { d -> remember(context, d) } }
        return bonded.firstOrNull { it.address == saved }
            ?: bonded.firstOrNull { isBuds(context, it) }?.also { remember(context, it) }
    }

    /** The paired devices that look like OPPO, OnePlus or realme earbuds, for the setup guide's picker. */
    fun candidates(context: Context): List<BluetoothDevice> = runCatching {
        context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty().filter { isBuds(context, it) }
    }.getOrDefault(emptyList())

    /** The address of the earbuds in use, or null before one was picked or found. */
    fun savedAddress(context: Context): String? = prefs(context).getString(KEY_ADDRESS, null)

    private fun prefs(context: Context) = context.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)
}
