package immortan.crypto

import android.os.Build
import android.security.keystore.{KeyGenParameterSpec, KeyProperties}
import scodec.bits.ByteVector

import java.security.KeyStore
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.{Cipher, KeyGenerator, SecretKey}
import scala.util.Try


// Encrypts small at-rest secrets (the wallet seed record) with a non-exportable AES key
// kept in AndroidKeyStore (TEE / StrongBox when the hardware has one). Symmetric keystore
// keys require API 23+, below that callers fall back to the legacy plaintext storage.
// Encrypted blob layout: 12-byte IV ++ ciphertext ++ 16-byte GCM tag.
object SecretCipher {
  private final val KEYSTORE_TYPE = "AndroidKeyStore"
  private final val KEY_ALIAS = "wallet_seed_at_rest_v1"
  private final val TRANSFORMATION = "AES/GCM/NoPadding"
  private final val IV_LENGTH_BYTES = 12
  private final val GCM_TAG_LENGTH_BITS = 128

  private def keystore: KeyStore = {
    val store = KeyStore.getInstance(KEYSTORE_TYPE)
    store.load(null)
    store
  }

  private def loadKey: Option[SecretKey] = {
    val store = keystore
    if (store.containsAlias(KEY_ALIAS)) Option(store.getKey(KEY_ALIAS, null).asInstanceOf[SecretKey]) else None
  }

  @android.annotation.TargetApi(Build.VERSION_CODES.M)
  private def getOrCreateKey: SecretKey = loadKey getOrElse {
    val spec = new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
      .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .setKeySize(256)
      .build

    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_TYPE)
    generator.init(spec)
    generator.generateKey
  }

  // None means a hardware-backed keystore is unusable on this device right now, caller falls back to plaintext
  def encryptIfPossible(plain: ByteVector): Option[ByteVector] = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) None else Try {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey)
    ByteVector.view(cipher.getIV) ++ ByteVector.view(cipher.doFinal(plain.toArray))
  }.toOption

  def decrypt(encrypted: ByteVector): ByteVector = {
    // A missing key here means the record can never be decrypted on this device (key invalidated or db moved across devices)
    val key = loadKey.getOrElse(throw new IllegalStateException("Seed encryption key is missing from AndroidKeyStore"))
    val (iv, body) = encrypted.splitAt(IV_LENGTH_BYTES)
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv.toArray))
    ByteVector.view(cipher.doFinal(body.toArray))
  }
}
