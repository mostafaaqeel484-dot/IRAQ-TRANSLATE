package com.iraqtranslate

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import org.conscrypt.Conscrypt
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Wireless ADB pairing/connection bridge with fixed diagnostic and audio-helper services. */
class AdbBridge private constructor(context: Context) {
    companion object {
        @Volatile private var instance: AdbBridge? = null
        fun get(context: Context): AdbBridge = instance ?: synchronized(this) {
            instance ?: AdbBridge(context.applicationContext).also { instance = it }
        }
    }

    private val manager = PersistentConnectionManager(context.applicationContext)

    fun pair(host: String, pairingPort: Int, pairingCode: String): Boolean {
        check(Build.VERSION.SDK_INT >= 30) { "Wireless debugging pairing requires Android 11+; use an enabled ADB TCP port on older versions" }
        require(host.isNotBlank()) { "Enter the TV IP address" }
        require(pairingPort in 1..65535) { "Invalid ADB pairing port" }
        require(pairingCode.matches(Regex("\\d{6}"))) { "ADB pairing code must contain six digits" }
        return manager.pair(host.trim(), pairingPort, pairingCode)
    }

    fun connect(host: String, connectPort: Int): Boolean {
        require(host.isNotBlank()) { "Enter the TV IP address" }
        require(connectPort in 1..65535) { "Invalid ADB connection port" }
        return manager.connect(host.trim(), connectPort)
    }

    fun isConnected(): Boolean = manager.isConnected()

    /** Starts the app_process helper under the ADB shell UID and returns its raw PCM stream. */
    fun openEchoReferenceSession(): EchoReferenceSession {
        check(manager.isConnected()) { "ADB is not connected" }
        val stream = manager.openStream("shell:")
        try {
            val command = "CLASSPATH=\$(pm path com.iraqtranslate | head -n 1 | cut -d: -f2) app_process /system/bin com.iraqtranslate.ShellAudioHelper\n"
            stream.openOutputStream().apply {
                write(command.toByteArray(StandardCharsets.UTF_8))
                flush()
            }
            val input = stream.openInputStream()
            val magic = ByteArray(8)
            val readError = AtomicReference<Throwable?>(null)
            val headerReady = CountDownLatch(1)
            Thread({
                try {
                    var offset = 0
                    while (offset < magic.size) {
                        val count = input.read(magic, offset, magic.size - offset)
                        if (count < 0) error("ADB shell helper exited before audio stream initialization")
                        offset += count
                    }
                } catch (t: Throwable) {
                    readError.set(t)
                } finally {
                    headerReady.countDown()
                }
            }, "iraq-translate-adb-header").apply { isDaemon = true; start() }
            if (!headerReady.await(20, TimeUnit.SECONDS)) {
                error("Timed out waiting for the ADB Echo Reference helper")
            }
            readError.get()?.let { throw it }
            if (!magic.contentEquals(EchoReferenceSession.MAGIC)) {
                val message = magic.toString(StandardCharsets.UTF_8).trim()
                error("Echo Reference helper did not start: $message")
            }
            return EchoReferenceSession(stream, input)
        } catch (t: Throwable) {
            runCatching { stream.close() }
            throw t
        }
    }

    /** Reads the active player's published MediaSession position when the IPTV player exposes one. */
    fun readMediaSessionPosition(): MediaSessionPositionParser.Position? {
        if (!manager.isConnected()) return null
        val dump = runCatching { runShell("dumpsys media_session") }.getOrNull() ?: return null
        return MediaSessionPositionParser.parse(dump, SystemClock.elapsedRealtime())
    }

    fun inspectAudioRoute(): String = runShell(
        "cmd package check-permission android.permission.CAPTURE_AUDIO_OUTPUT com.android.shell; " +
            "dumpsys media.audio_policy | grep -i -E 'echo reference|tuner|input device|input source|port id'; " +
            "dumpsys media.audio_flinger | grep -i -E 'echo reference|tuner|input thread|record thread'"
    )

    fun deviceSummary(): String = runShell("getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk")

    private fun runShell(command: String): String {
        check(manager.isConnected()) { "ADB is not connected" }
        var stream: AdbStream? = null
        try {
            stream = manager.openStream("shell:")
            stream.openOutputStream().apply {
                write((command + "\nexit\n").toByteArray(StandardCharsets.UTF_8))
                flush()
            }
            val bytes = ByteArrayOutputStream()
            stream.openInputStream().use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    bytes.write(buffer, 0, count)
                    if (bytes.size() > 256 * 1024) break
                }
            }
            return bytes.toString("UTF-8").trim().ifBlank { "ADB shell completed without output" }
        } finally {
            runCatching { stream?.close() }
        }
    }

    fun disconnect() = manager.disconnect()

    class EchoReferenceSession internal constructor(
        private val stream: AdbStream,
        val input: InputStream
    ) : Closeable {
        companion object { val MAGIC = "IRQAUD01".toByteArray(StandardCharsets.US_ASCII) }
        override fun close() {
            runCatching { input.close() }
            runCatching { stream.close() }
        }
    }

    private class PersistentConnectionManager(context: Context) : AbsAdbConnectionManager() {
        private val prefs = context.getSharedPreferences("iraq_translate_adb", Context.MODE_PRIVATE)
        private var privateKey: PrivateKey
        private var certificate: Certificate

        init {
            if (Security.getProvider("Conscrypt") == null) {
                Security.insertProviderAt(Conscrypt.newProvider(), 1)
            }
            setApi(Build.VERSION.SDK_INT)
            val savedPrivate = prefs.getString("private_key", null)
            val savedCertificate = prefs.getString("certificate", null)
            val restored = if (savedPrivate != null && savedCertificate != null) runCatching {
                val restoredKey = KeyFactory.getInstance("RSA").generatePrivate(
                    PKCS8EncodedKeySpec(Base64.decode(savedPrivate, Base64.DEFAULT))
                )
                val restoredCertificate = CertificateFactory.getInstance("X.509").generateCertificate(
                    Base64.decode(savedCertificate, Base64.DEFAULT).inputStream()
                )
                restoredKey to restoredCertificate
            }.getOrNull() else null
            if (restored != null) {
                privateKey = restored.first
                certificate = restored.second
            } else {
                val pair = KeyPairGenerator.getInstance("RSA").apply {
                    initialize(2048, SecureRandom())
                }.generateKeyPair()
                privateKey = pair.private
                certificate = createCertificate(pair.public, privateKey)
                check(prefs.edit()
                    .putString("private_key", Base64.encodeToString(privateKey.encoded, Base64.NO_WRAP))
                    .putString("certificate", Base64.encodeToString(certificate.encoded, Base64.NO_WRAP))
                    .commit()) { "Could not persist ADB authentication identity" }
            }
        }

        override fun getPrivateKey(): PrivateKey = privateKey
        override fun getCertificate(): Certificate = certificate
        override fun getDeviceName(): String = "IRAQ TRANSLATE"

        private fun createCertificate(publicKey: java.security.PublicKey, key: PrivateKey): Certificate {
            val algorithm = "SHA512withRSA"
            val now = System.currentTimeMillis()
            val notBefore = Date(now - 60_000L)
            val notAfter = Date(now + 10L * 365 * 24 * 60 * 60 * 1000)
            val owner = X500Name("CN=IRAQ TRANSLATE")
            val extensions = CertificateExtensions().apply {
                set("SubjectKeyIdentifier", SubjectKeyIdentifierExtension(KeyIdentifier(publicKey).identifier))
                set("PrivateKeyUsage", PrivateKeyUsageExtension(notBefore, notAfter))
            }
            val info = X509CertInfo().apply {
                set("version", CertificateVersion(2))
                set("serialNumber", CertificateSerialNumber(SecureRandom().nextInt() and Int.MAX_VALUE))
                set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithm)))
                set("subject", CertificateSubjectName(owner))
                set("key", CertificateX509Key(publicKey))
                set("validity", CertificateValidity(notBefore, notAfter))
                set("issuer", CertificateIssuerName(owner))
                set("extensions", extensions)
            }
            val cert = X509CertImpl(info)
            cert.sign(key, algorithm)
            return cert
        }
    }
}
