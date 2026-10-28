package immortan.crypto

import fr.acinq.bitcoin.Crypto
import fr.acinq.eclair.randomBytes
import fr.acinq.eclair.wire.CommonCodecs.text
import scodec.bits.ByteVector
import scodec.codecs.bytes

import scala.util.Try

case class ChannelBackup(database: ByteVector, signetId: Option[String])

object ChannelBackup {
  // Version 0 is the original encrypted database; version 1 also encrypts the signet identity.
  private val signetCodec = text ~ bytes

  def encrypt(backup: ChannelBackup, seed: ByteVector): ByteVector = {
    val (version, plain) = backup.signetId match {
      case Some(id) => (1.toByte, signetCodec.encode((id, backup.database)).require.bytes)
      case None => (0.toByte, backup.database)
    }
    version +: Tools.chaChaEncrypt(Crypto.sha256(seed), randomBytes(12), plain)
  }

  def decrypt(backup: ByteVector, seed: ByteVector): Try[ChannelBackup] = for {
    version <- Try {
      require(backup.nonEmpty && (backup.head == 0 || backup.head == 1), "Unsupported channel backup version")
      backup.head
    }
    plain <- Tools.chaChaDecrypt(Crypto.sha256(seed), backup.drop(1))
    decoded <- Try {
      if (version == 0) ChannelBackup(plain, None) else {
        val (id, database) = signetCodec.decode(plain.bits).require.value
        ChannelBackup(database, Some(id))
      }
    }
  } yield decoded
}
