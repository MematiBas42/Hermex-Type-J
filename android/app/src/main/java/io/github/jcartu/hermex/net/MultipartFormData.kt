package io.github.jcartu.hermex.net

import okio.Buffer
import java.util.UUID

/**
 * Port of `Networking/MultipartFormData.swift` — a `multipart/form-data` body
 * builder emitting byte-identical field encodings (boundaries, CRLFs,
 * dispositions) to the Swift helpers used by the upload endpoint.
 */
internal class MultipartFormData(
    val boundary: String = "Boundary-${UUID.randomUUID()}",
) {
    private val buffer = Buffer()

    val contentType: String get() = "multipart/form-data; boundary=$boundary"

    fun appendTextField(name: String, value: String) {
        buffer.writeUtf8("--$boundary\r\n")
        buffer.writeUtf8("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        buffer.writeUtf8("$value\r\n")
    }

    fun appendFileField(name: String, filename: String, data: ByteArray) {
        buffer.writeUtf8("--$boundary\r\n")
        buffer.writeUtf8("Content-Disposition: form-data; name=\"$name\"; filename=\"$filename\"\r\n")
        buffer.writeUtf8("Content-Type: application/octet-stream\r\n\r\n")
        buffer.write(data)
        buffer.writeUtf8("\r\n")
    }

    fun build(): ByteArray {
        buffer.writeUtf8("--$boundary--\r\n")
        return buffer.readByteArray()
    }
}
