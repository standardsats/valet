package immortan.utils

import fr.acinq.eclair.blockchain.fee.{FeeratePerByte, FeeratePerKw}
import org.junit.Assert.assertEquals
import org.junit.Test

class FeeRatesSpec {
  @Test
  def preservesMillisatoshiPerVbyteEstimates: Unit = {
    val provider = new EsploraFeeProvider("")
    val rates = provider.parseFeeRates("""{"1":1.069,"2":0.345,"1008":0.09999999999999999,"warning":"ignored"}""")

    assertEquals(1069L, provider.extractFeerate(rates, 1).toLong)
    assertEquals(100L, provider.extractFeerate(rates, 1008).toLong)
    assertEquals(104L, FeeratePerByte(FeeratePerKw(provider.extractFeerate(rates, 1008))).feerate.toLong)
  }
}
