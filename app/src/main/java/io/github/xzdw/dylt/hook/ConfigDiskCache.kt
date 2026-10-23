package io.github.xzdw.dylt.hook

import android.content.Context
import android.os.Parcel
import android.util.Log
import io.github.xzdw.dylt.config.GlassConfig
import java.io.File

/**
 * Last configuration this target process successfully read, kept in the target's own private
 * storage.
 *
 * The module's preferences are private to the module's uid, and the exported provider only
 * answers while the module process is alive (HyperOS freezes or kills it as soon as the manager
 * UI is left). A target with no copy of its own therefore falls back to the defaults, which is
 * why every rendering parameter reverted once the module was closed.
 *
 * This cache is written by our own hook code, which runs inside the target process: no
 * permission, no prompt, nothing outside the app's own sandbox. Newly added configuration fields
 * travel automatically because the payload is a marshalled [GlassConfig.toBundle] bundle.
 */
internal object ConfigDiskCache {
    private const val TAG = "OfflineGlassGeom"
    private const val FILE_NAME = "liquidtab_config.bin"
    private const val MAGIC = 0x4C54_4142 // "LTAB"
    private const val VERSION = 1

    fun write(context: Context, packageName: String, config: GlassConfig) {
        runCatching {
            val parcel = Parcel.obtain()
            val bytes = try {
                parcel.writeInt(MAGIC)
                parcel.writeInt(VERSION)
                parcel.writeString(packageName)
                parcel.writeBundle(config.toBundle(packageName))
                parcel.marshall()
            } finally {
                parcel.recycle()
            }
            val target = file(context)
            val temporary = File(target.parentFile, "$FILE_NAME.tmp")
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(target)) {
                // renameTo can fail when the target already exists on some filesystems.
                target.writeBytes(bytes)
                temporary.delete()
            }
        }.onFailure {
            Log.w(TAG, "config cache write failed: ${it.javaClass.simpleName} ${it.message}")
        }
    }

    fun read(context: Context, packageName: String): GlassConfig? = runCatching {
        val target = file(context)
        if (!target.isFile) return@runCatching null
        val bytes = target.readBytes()
        val parcel = Parcel.obtain()
        try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            if (parcel.readInt() != MAGIC || parcel.readInt() != VERSION) return@runCatching null
            if (parcel.readString() != packageName) return@runCatching null
            val bundle = parcel.readBundle(GlassConfig::class.java.classLoader)
                ?: return@runCatching null
            GlassConfig.fromBundle(bundle)
        } finally {
            parcel.recycle()
        }
    }.getOrNull()

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)
}
