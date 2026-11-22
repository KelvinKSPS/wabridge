package com.orangepi.wabridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context

/**
 * Paired watches and headphones, for the "only ring when the watch is connected" gate.
 *
 * Without the BLUETOOTH_CONNECT permission (Android 12+) everything returns empty/false,
 * so the app never crashes because of it.
 */
object BluetoothHelper {

    data class Device(val name: String, val address: String)

    /** Paired devices, in alphabetical order. */
    @SuppressLint("MissingPermission")
    fun paired(context: Context): List<Device> {
        val adapter = adapter(context) ?: return emptyList()
        return runCatching {
            adapter.bondedDevices
                ?.filter { it.name?.isNotBlank() == true }
                ?.map { Device(it.name, it.address) }
                ?.sortedBy { it.name.lowercase() }
                ?: emptyList()
        }.getOrElse { emptyList() }
    }

    /** true if the device is connected (tries the hidden API first, then known profiles). */
    @SuppressLint("MissingPermission")
    fun isConnected(context: Context, address: String): Boolean {
        val manager = manager(context) ?: return false
        val adapter = adapter(context) ?: return false
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return false
        return runCatching {
            // Hidden API available since API 11, works with proprietary profiles (watches)
            val m = device.javaClass.getMethod("isConnected")
            m.invoke(device) as? Boolean == true
        }.getOrElse {
            // fallback: standard profiles
            runCatching {
                connected(manager, BluetoothProfile.HEADSET, address) ||
                    connected(manager, BluetoothProfile.A2DP, address) ||
                    connected(manager, BluetoothProfile.GATT, address)
            }.getOrDefault(false)
        }
    }

    /** true when some device is connected on the hands-free (call audio) profile. */
    @SuppressLint("MissingPermission")
    fun headsetConnected(context: Context): Boolean = runCatching {
        adapter(context)?.getProfileConnectionState(BluetoothProfile.HEADSET) ==
            BluetoothProfile.STATE_CONNECTED
    }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    private fun connected(manager: BluetoothManager, profile: Int, address: String): Boolean =
        manager.getConnectedDevices(profile)?.any { it.address == address } == true

    private fun manager(context: Context): BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private fun adapter(context: Context): BluetoothAdapter? = manager(context)?.adapter
}
