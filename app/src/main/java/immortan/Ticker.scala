package immortan

/// When adding new ticker you should define case class and add
/// it to the 'knownTickers' field.
trait Ticker {
  def tag: String
}

case class USD() extends Ticker {
  def tag = "USD"
}

case class EUR() extends Ticker {
  def tag = "EUR"
}

/// Standard Hosted Channel ticker - satoshi denominated, no fiat conversion
case class SAT() extends Ticker {
  def tag = "SAT"
}

object Ticker {
  final val USD_TICKER = USD()
  final val EUR_TICKER = EUR()
  final val SAT_TICKER = SAT()

  final val knownTickers: Set[Ticker] = Set(
    USD_TICKER,
    EUR_TICKER,
    SAT_TICKER
  )

  /// Check if ticker is a fiat ticker (requires rate conversion)
  def isFiat(ticker: Ticker): Boolean = ticker match {
    case SAT() => false
    case _ => true
  }

  /// Check if ticker is standard hosted channel (no rate conversion)
  def isHostedChannel(ticker: Ticker): Boolean = ticker match {
    case SAT() => true
    case _ => false
  }

  def tickerByTag(tag: String): Option[Ticker] = {
    knownTickers.find(_.tag == tag)
  }
}
