package dev.wordprism.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dev.wordprism.contract.Id
import dev.wordprism.engine.CredentialStore
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-bound encryption; encrypted records are also excluded from Android Auto Backup. */
class KeystoreCredentials(context: Context) : CredentialStore {
    private val directory = File(context.noBackupFilesDir, "wordprism-credentials").also { check(it.mkdirs() || it.isDirectory) }
    private val alias = "wordprism.credentials.v1"
    private fun key(): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keystore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    private fun file(id: Id): AtomicFile {
        val name = MessageDigest.getInstance("SHA-256").digest(id.value.toByteArray()).joinToString("") { "%02x".format(it) }
        return AtomicFile(File(directory, name))
    }
    @Synchronized override fun has(presetId: Id): Boolean = read(presetId)?.let { it.fill('\u0000'); true } ?: false
    @Synchronized override fun read(presetId: Id): CharArray? = runCatching {
        val data = file(presetId).readFully(); require(data.size in 29..8192)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        cipher.updateAAD(presetId.value.toByteArray())
        val bytes = cipher.doFinal(data.copyOfRange(12, data.size))
        try { Charsets.UTF_8.decode(ByteBuffer.wrap(bytes)).let { chars -> CharArray(chars.remaining()).also { chars.get(it) } } } finally { bytes.fill(0) }
    }.getOrNull()
    @Synchronized override fun write(presetId: Id, secret: CharArray) {
        val bytesBuffer = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(secret))
        val bytes = ByteArray(bytesBuffer.remaining()).also { bytesBuffer.get(it) }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(presetId.value.toByteArray())
            val data = cipher.iv + cipher.doFinal(bytes); val destination = file(presetId); val stream = destination.startWrite()
            try { stream.write(data); destination.finishWrite(stream) } catch (error: Throwable) { destination.failWrite(stream); throw error }
        } finally { bytes.fill(0); if (bytesBuffer.hasArray()) bytesBuffer.array().fill(0); secret.fill('\u0000') }
    }
    @Synchronized override fun delete(presetId: Id) { file(presetId).delete() }
}
