package com.orangepi.wabridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context

/**
 * Relógios e fones pareados, para o gate "só ligar com o relógio conectado".
 *
 * Sem a permissão BLUETOOTH_CONNECT (Android 12+) tudo devolve vazio/falso —
 * o app nunca trava por causa disso.
 */
object BluetoothHelper {

    data class Device(val name: String, val address: String)

    /** Aparelhos pareados, em ordem alfabética. */
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

    /** true se o aparelho está conectado (tenta API oculta primeiro, depois perfis conhecidos). */
    @SuppressLint("MissingPermission")
    fun isConnected(context: Context, address: String): Boolean {
        val manager = manager(context) ?: return false
        val adapter = adapter(context) ?: return false
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return false
        return runCatching {
            // API oculta disponível desde API 11 — funciona com perfis proprietários (relógios)
            val m = device.javaClass.getMethod("isConnected")
            m.invoke(device) as? Boolean == true
        }.getOrElse {
            // fallback: perfis padrão
            runCatching {
                connected(manager, BluetoothProfile.HEADSET, address) ||
                    connected(manager, BluetoothProfile.A2DP, address) ||
                    connected(manager, BluetoothProfile.GATT, address)
            }.getOrDefault(false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connected(manager: BluetoothManager, profile: Int, address: String): Boolean =
        manager.getConnectedDevices(profile)?.any { it.address == address } == true

    private fun manager(context: Context): BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private fun adapter(context: Context): BluetoothAdapter? = manager(context)?.adapter
}
