package finance.valet.nwc

import com.samourai.wallet.schnorr.Schnorr
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Crypto}
import immortan.crypto.Tools.Bytes
import immortan.utils.AES
import scodec.bits.ByteVector

import java.security.SecureRandom
import javax.crypto.Cipher


/**
 * NIP-04 compatible encryption for Nostr Wallet Connect.
 *
 * Implements the encryption scheme used by NWC:
 * - ECDH shared secret derivation using secp256k1
 * - AES-256-CBC encryption with random IV
 * - Base64 encoding for transport
 */
object NWCCrypto {

  private val random = new SecureRandom()

  /**
   * Generate a new random keypair for NWC connection.
   */
  def generateKeyPair(): (PrivateKey, PublicKey) = {
    val privKeyBytes = new Array[Byte](32)
    random.nextBytes(privKeyBytes)
    val privateKey = PrivateKey(ByteVector32(ByteVector.view(privKeyBytes)))
    (privateKey, privateKey.publicKey)
  }

  /**
   * Derive shared secret using ECDH.
   * NIP-04 uses the x-coordinate of the ECDH point as the shared secret.
   */
  def getSharedSecret(myPrivateKey: PrivateKey, theirPublicKey: PublicKey): ByteVector32 = {
    // Use secp256k1 ECDH - this gives us sha256(compressed_point)
    // For NIP-04, we need the raw x-coordinate, so we compute it differently
    val sharedPoint = theirPublicKey.multiply(myPrivateKey)
    // Get the x-coordinate (first 32 bytes of uncompressed point, after the 04 prefix)
    val uncompressed = sharedPoint.toUncompressedBin
    ByteVector32(uncompressed.drop(1).take(32))
  }

  /**
   * Encrypt a message using NIP-04 scheme.
   *
   * @param plaintext The message to encrypt
   * @param myPrivateKey Our private key
   * @param theirPublicKey Recipient's public key
   * @return Encrypted message in format: base64(ciphertext)?iv=base64(iv)
   */
  def encrypt(plaintext: String, myPrivateKey: PrivateKey, theirPublicKey: PublicKey): String = {
    val sharedSecret = getSharedSecret(myPrivateKey, theirPublicKey)

    // Generate random 16-byte IV
    val iv = new Array[Byte](16)
    random.nextBytes(iv)

    // Encrypt using AES-256-CBC
    val plaintextBytes = plaintext.getBytes("UTF-8")
    val ciphertext = AES.encode(plaintextBytes, sharedSecret.toArray, iv)

    // Format: base64(ciphertext)?iv=base64(iv)
    val ciphertextBase64 = java.util.Base64.getEncoder.encodeToString(ciphertext.toArray)
    val ivBase64 = java.util.Base64.getEncoder.encodeToString(iv)

    s"$ciphertextBase64?iv=$ivBase64"
  }

  /**
   * Decrypt a message using NIP-04 scheme.
   *
   * @param encrypted Encrypted message in format: base64(ciphertext)?iv=base64(iv)
   * @param myPrivateKey Our private key
   * @param theirPublicKey Sender's public key
   * @return Decrypted plaintext
   */
  def decrypt(encrypted: String, myPrivateKey: PrivateKey, theirPublicKey: PublicKey): scala.util.Try[String] = scala.util.Try {
    val sharedSecret = getSharedSecret(myPrivateKey, theirPublicKey)

    // Parse format: base64(ciphertext)?iv=base64(iv)
    val parts = encrypted.split("\\?iv=")
    require(parts.length == 2, "Invalid encrypted message format")

    val ciphertext = java.util.Base64.getDecoder.decode(parts(0))
    val iv = java.util.Base64.getDecoder.decode(parts(1))

    // Decrypt using AES-256-CBC
    val plaintext = AES.decode(ciphertext, sharedSecret.toArray, iv)
    new String(plaintext.toArray, "UTF-8")
  }

  /**
   * Compute the event ID (SHA256 hash of serialized event).
   *
   * @param serializedEvent JSON array: [0, pubkey, created_at, kind, tags, content]
   * @return 32-byte event ID
   */
  def computeEventId(serializedEvent: String): ByteVector32 = {
    Crypto.sha256(ByteVector.view(serializedEvent.getBytes("UTF-8")))
  }

  /**
   * Sign an event ID using Schnorr signature (BIP-340).
   * Nostr requires BIP-340 Schnorr signatures.
   *
   * @param eventId The event ID to sign (32-byte hash)
   * @param privateKey The private key to sign with
   * @return 64-byte Schnorr signature
   */
  def signEvent(eventId: ByteVector32, privateKey: PrivateKey): ByteVector64 = {
    // Generate random aux bytes for Schnorr signing
    val auxRand = new Array[Byte](32)
    random.nextBytes(auxRand)

    // Sign using BIP-340 Schnorr
    val sig = Schnorr.sign(eventId.toArray, privateKey.value.toArray, auxRand)
    ByteVector64(ByteVector.view(sig))
  }

  /**
   * Verify an event signature.
   *
   * @param eventId The event ID
   * @param signature The signature to verify
   * @param publicKey The public key to verify against
   * @return true if signature is valid
   */
  def verifySignature(eventId: ByteVector32, signature: ByteVector64, publicKey: PublicKey): Boolean = {
    Crypto.verifySignature(eventId, signature, publicKey)
  }

  /**
   * Convert a public key to Nostr npub format (hex for now, bech32 later).
   * Nostr uses the x-coordinate only (32 bytes) for public keys.
   */
  def pubkeyToNostrHex(publicKey: PublicKey): String = {
    // Nostr uses x-only pubkeys (32 bytes), drop the prefix byte
    publicKey.value.drop(1).toHex
  }

  /**
   * Convert a Nostr hex pubkey back to a PublicKey.
   * Assumes even Y coordinate (prefix 02).
   */
  def nostrHexToPubkey(hex: String): PublicKey = {
    // Add 02 prefix for compressed format (assume even Y)
    PublicKey(ByteVector.fromValidHex("02" + hex))
  }

  /**
   * Generate a random 32-byte secret for NWC connection.
   */
  def generateSecret(): ByteVector32 = {
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    ByteVector32(ByteVector.view(bytes))
  }
}
