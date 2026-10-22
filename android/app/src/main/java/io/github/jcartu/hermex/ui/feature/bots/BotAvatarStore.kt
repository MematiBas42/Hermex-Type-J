package io.github.jcartu.hermex.ui.feature.bots

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.BotProfile
import io.github.jcartu.hermex.net.hermes.HermesCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Base64

/**
 * Port of `BotAvatarStore` (`Features/Bots/BotAvatarStore.swift`): decoded Desktop avatars for
 * the Bots inbox, read through `profiles.get_asset`. Keyed by connection UUID plus Profile
 * name so equal Profile names on two hosts never share an image. Entries exist only for the
 * current connection's roster and drop when the connection is replaced or removed; nothing is
 * written to disk. Desktop's copies of a drawn face are skipped ([asset]), so the face stays live.
 *
 * One instance per process (`AppContainer.botAvatars`), shared by the inbox, the editor and
 * the chat's title pill. [decoder] turns image bytes into a bounded bitmap; tests inject one.
 */
class BotAvatarStore(private val decoder: BotAvatarDecoder = BotAvatarDecoder.Android) {
    private data class Key(val connectionID: String, val profile: String)

    /** A null image is Desktop's face copy, kept so an unchanged revision does not fetch it again. */
    private data class Entry(val revision: Int?, val image: Bitmap?)

    private val lock = Any()
    private val entries = HashMap<Key, Entry>()
    private var generation = 0L
    private val _version = MutableStateFlow(0)

    /** Bumps on every change, so a composition can re-read [images] without holding bitmaps in state. */
    val version: StateFlow<Int> = _version.asStateFlow()

    /** Images for one connection's roster, keyed by Profile name. */
    fun images(connectionID: String): Map<String, Bitmap> = synchronized(lock) {
        entries.entries.filter { it.key.connectionID == connectionID && it.value.image != null }
            .associate { it.key.profile to it.value.image!! }
    }

    fun image(connectionID: String, profile: String): Bitmap? = synchronized(lock) {
        entries[Key(connectionID, profile)]?.image
    }

    fun removeAll(connectionID: String) {
        synchronized(lock) {
            generation++
            entries.keys.removeAll { it.connectionID == connectionID }
        }
        bump()
    }

    /**
     * Applies an acknowledged editor write immediately. Keeping the current look revision is
     * intentional: replacing only the asset does not advance UI metadata, so a later roster
     * refresh must retain this new thumbnail.
     */
    fun setImage(image: Bitmap?, connectionID: String, profile: String, revision: Int?) {
        synchronized(lock) {
            generation++
            val key = Key(connectionID, profile)
            if (image != null) entries[key] = Entry(revision, image) else entries.remove(key)
        }
        bump()
    }

    /**
     * Brings the store in line with a fresh roster for one connection. Every other connection's
     * images and rows no longer flagged `has_avatar` drop first, then [onUpdate] fires so cached
     * images paint before any fetch. Images whose look revision is unchanged are kept; the rest,
     * including entries without a revision, are fetched one at a time and [onUpdate] fires after
     * each decode. The first failed call ends the pass silently: the roster never depends on it.
     */
    suspend fun refresh(
        profiles: List<BotProfile>,
        connectionID: String,
        transport: BotTransport,
        validateDispatch: (() -> Unit)? = null,
        onUpdate: () -> Unit,
    ) {
        val wanted = profiles.filter { it.hasAvatar }.map { it.id }.toSet()
        val owner = synchronized(lock) {
            generation++
            entries.keys.removeAll { it.connectionID != connectionID || it.profile !in wanted }
            generation
        }
        bump()
        onUpdate()
        for (profile in profiles) {
            if (profile.id !in wanted) continue
            val key = Key(connectionID, profile.id)
            val known = synchronized(lock) { entries[key] }
            if (known != null && known.revision != null && known.revision == profile.lookRevision) continue
            val reply = try {
                transport.call(HermesCall.ProfilesGetAsset(profile.id), validateDispatch)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                return
            }
            if (!currentCoroutineContext().isActive) return
            val asset = withContext(Dispatchers.Default) { asset(reply, profile.isPhoto, decoder) }
            if (!currentCoroutineContext().isActive) return
            synchronized(lock) {
                if (generation != owner) return
                if (asset != null) entries[key] = Entry(profile.lookRevision, asset.image) else entries.remove(key)
            }
            bump()
            onUpdate()
        }
    }

    private fun bump() {
        _version.update { it + 1 }
    }

    /** A readable asset: a picture to show, or Desktop's still copy of a drawn face. */
    sealed class Asset {
        data class Image(val bitmap: Bitmap) : Asset()
        object FaceCopy : Asset()

        val image: Bitmap? get() = (this as? Image)?.bitmap
    }

    companion object {
        /** Longest decoded edge in pixels: the 48dp row tile at 3x with headroom. */
        const val MAX_PIXEL_SIZE = 192

        /** Largest data URL accepted from the host. The server caps assets at 2 MB before base64. */
        const val MAX_PAYLOAD_BYTES = 3_000_000

        /** Decodes a `profiles.get_asset` reply into a bounded thumbnail; null means the row draws its face. */
        fun decode(reply: BotJSON, photo: Boolean, decoder: BotAvatarDecoder = BotAvatarDecoder.Android): Bitmap? =
            asset(reply, photo, decoder)?.image

        /**
         * Reads a `profiles.get_asset` reply. Anything other than a found, base64 image data URL
         * within the size cap is null, so a later fetch retries it. Unless the look says `photo`, a
         * 160x160 PNG is [Asset.FaceCopy]: Desktop uploads a still copy of its own drawing at that
         * size for any bot without an asset (`isBackfilledFacePng`).
         */
        fun asset(reply: BotJSON, photo: Boolean, decoder: BotAvatarDecoder = BotAvatarDecoder.Android): Asset? {
            if (reply["found"].flag != true) return null
            val text = reply["data"].text ?: return null
            if (text.length > MAX_PAYLOAD_BYTES) return null
            val bytes = dataURLBytes(text) ?: return null
            val bitmap = decoder.decode(bytes, MAX_PIXEL_SIZE) ?: return null
            if (!photo && isDesktopFaceCopy(bytes)) return Asset.FaceCopy
            return Asset.Image(bitmap)
        }

        /** The decoded bytes of a `data:image/…;base64,…` URL, or null for any other text. */
        fun dataURLBytes(text: String): ByteArray? {
            if (!text.startsWith("data:image/")) return null
            val comma = text.indexOf(',')
            if (comma < 0 || !text.substring(0, comma).endsWith(";base64")) return null
            return runCatching { Base64.getDecoder().decode(text.substring(comma + 1)) }.getOrNull()
        }

        /**
         * `isDesktopFaceCopy` — a PNG whose IHDR says 160x160, read from the header bytes alone:
         * the eight-byte signature, the IHDR length and type, then width and height big-endian.
         */
        fun isDesktopFaceCopy(bytes: ByteArray): Boolean {
            if (bytes.size < 24) return false
            val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            for (index in signature.indices) if (bytes[index] != signature[index]) return false
            if (bytes[12] != 'I'.code.toByte() || bytes[13] != 'H'.code.toByte() || bytes[14] != 'D'.code.toByte() || bytes[15] != 'R'.code.toByte()) return false
            fun int(at: Int): Int = ((bytes[at].toInt() and 0xff) shl 24) or ((bytes[at + 1].toInt() and 0xff) shl 16) or
                ((bytes[at + 2].toInt() and 0xff) shl 8) or (bytes[at + 3].toInt() and 0xff)
            return int(16) == 160 && int(20) == 160
        }
    }
}

/** Turns encoded image bytes into a bitmap no longer than `maxPixelSize` on its longest edge. */
fun interface BotAvatarDecoder {
    fun decode(bytes: ByteArray, maxPixelSize: Int): Bitmap?

    companion object {
        /** `BitmapFactory` with a power-of-two sample size, then an exact bound. */
        val Android: BotAvatarDecoder = BotAvatarDecoder { bytes, maxPixelSize ->
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPixelSize) sample *= 2
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: return@runCatching null
                thumbnail(decoded, maxPixelSize)
            }.getOrNull()
        }

        /** `BotAvatarStore.thumbnail` — bounds a bitmap to `maxPixelSize` on its longest edge. */
        fun thumbnail(image: Bitmap, maxPixelSize: Int = BotAvatarStore.MAX_PIXEL_SIZE): Bitmap {
            val longest = maxOf(image.width, image.height)
            if (longest <= maxPixelSize) return image
            val scale = maxPixelSize.toDouble() / longest
            val width = (image.width * scale).toInt().coerceAtLeast(1)
            val height = (image.height * scale).toInt().coerceAtLeast(1)
            return Bitmap.createScaledBitmap(image, width, height, true)
        }
    }
}
