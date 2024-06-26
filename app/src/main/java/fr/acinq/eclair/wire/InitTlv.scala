package fr.acinq.eclair.wire

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.UInt64
import fr.acinq.eclair.wire.CommonCodecs._
import scodec.Codec
import scodec.codecs.{discriminated, list, variableSizeBytesLong}


/** Tlv types used inside Init messages. */
sealed trait InitTlv extends Tlv

object InitTlv {

  /** The chains the node is interested in. */
  case class Networks(chainHashes: List[ByteVector32]) extends InitTlv

}

object InitTlvCodecs {

  import InitTlv._

  private val networks: Codec[Networks] = variableSizeBytesLong(varintoverflow, list(bytes32)).as[Networks]

  val initTlvCodec: Codec[TlvStream[InitTlv]] = TlvCodecs.tlvStream(discriminated[InitTlv].by(varint)
    .typecase(UInt64(1), networks)
  )

}