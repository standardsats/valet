package fr.acinq.eclair

import fr.acinq.bitcoin.Crypto
import immortan.crypto.{ChannelBackup, Tools}
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test
import scodec.bits.ByteVector

class ChannelBackupSpec {
  val seed: ByteVector = ByteVector.fill(32)(1)
  val database: ByteVector = ByteVector.fill(256)(42)

  @Test
  def signetIdentitySurvivesEncryption: Unit = {
    for (id <- List("mutinynet", "custom:050b6578616d706c652e636f6dc351")) {
      val backup = ChannelBackup(database, Some(id))
      assertEquals(backup, ChannelBackup.decrypt(ChannelBackup.encrypt(backup, seed), seed).get)
    }
  }

  @Test
  def legacyBackupsHaveNoImplicitNetwork: Unit = {
    val legacy = 0.toByte +: Tools.chaChaEncrypt(Crypto.sha256(seed), ByteVector.fill(12)(2), database)
    assertEquals(ChannelBackup(database, None), ChannelBackup.decrypt(legacy, seed).get)
    val encrypted = ChannelBackup.encrypt(ChannelBackup(database, None), seed)
    assertEquals(database, Tools.chaChaDecrypt(Crypto.sha256(seed), encrypted.drop(1)).get)
  }

  @Test
  def invalidBackupsAreRejected: Unit = {
    val encrypted = ChannelBackup.encrypt(ChannelBackup(database, Some("mutinynet")), seed)
    assertTrue(ChannelBackup.decrypt(encrypted, ByteVector.fill(32)(3)).isFailure)
    assertTrue(ChannelBackup.decrypt(encrypted.dropRight(1), seed).isFailure)
    assertTrue(ChannelBackup.decrypt(2.toByte +: encrypted.drop(1), seed).isFailure)
    assertTrue(ChannelBackup.decrypt(ByteVector.empty, seed).isFailure)
    val malformed = 1.toByte +: Tools.chaChaEncrypt(Crypto.sha256(seed), ByteVector.fill(12)(2), ByteVector.empty)
    assertTrue(ChannelBackup.decrypt(malformed, seed).isFailure)
  }
}
