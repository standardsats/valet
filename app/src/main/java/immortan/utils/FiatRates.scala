package immortan.utils

import immortan.utils.ImplicitJsonFormats._
import immortan.crypto.{CanBeShutDown, Tools}
import immortan.{DataBag, LNParams}
import rx.lang.scala.{Observable, Subscription}


object FiatRates {
  type BlockchainInfoItemMap = Map[String, BlockchainInfoItem]
  type CoinGeckoItemMap = Map[String, CoinGeckoItem]
  type BitpayItemList = List[BitpayItem]
}

class FiatRates(bag: DataBag) extends CanBeShutDown {
  override def becomeShutDown: Unit = listeners = Set.empty

  val customFiatSymbols: Map[String, String] = Map("rub" -> "\u20BD", "usd" -> "$", "inr" -> "₹", "gbp" -> "£", "cny" -> "CN¥", "jpy" -> "¥", "brl" -> "R$", "eur" -> "€", "krw" -> "₩",
    "cym" -> "￠", "lvl" -> "ℒ\uD835\uDCC8", "svc" -> "₡", "frf" -> "₣", "brl" -> "R$", "sos" -> "Sh.So.")

  val universallySupportedSymbols: Map[String, String] = Map("usd" -> "US Dollar", "eur" -> "Euro", "jpy" -> "Japanese Yen", "cny" -> "Chinese Yuan", "inr" -> "Indian Rupee", "cad" -> "Canadian Dollar",
    "rub" -> "Русский Рубль", "brl" -> "Real Brasileiro", "czk" -> "Česká Koruna", "gbp" -> "Pound Sterling", "aud" -> "Australian Dollar", "try" -> "Turkish Lira", "nzd" -> "New Zealand Dollar",
    "thb" -> "Thai Baht", "twd" -> "New Taiwan Dollar", "krw" -> "South Korean won", "clp" -> "Chilean Peso", "sgd" -> "Singapore Dollar", "hkd" -> "Hong Kong Dollar", "pln" -> "Polish złoty",
    "dkk" -> "Danish Krone", "sek" -> "Swedish Krona", "chf" -> "Swiss franc", "huf" -> "Hungarian forint", "cym" -> "Welsh Pound", "lvl" -> "Latvian lat", "dm" -> "Deutsche Mark",
    "frf" -> "French franc", "svc" -> "Salvadoran colón", "esd" -> "Salvadoran dollar", "sps" -> "Salvadoran peso", "eip" -> "Punt na hÉireann", "brl" -> "Brazilian Real", "sos" -> "Somali Shilling")

  def reloadData: Tools.Fiat2Btc = focused.map(_.toLowerCase) match {
    case Some("sos") => to[Bitpay](LNParams.connectionProvider.get("https://bitpay.com/rates").string).data.map { case BitpayItem(code, rate) => code.toLowerCase -> rate }.toMap
    case _ => fr.acinq.eclair.secureRandom nextInt 3 match {
      case 0 => to[CoinGecko](LNParams.connectionProvider.get("https://api.coingecko.com/api/v3/exchange_rates").string).rates.map { case (code, item) => code.toLowerCase -> item.value }
      case 1 => to[FiatRates.BlockchainInfoItemMap](LNParams.connectionProvider.get("https://blockchain.info/ticker").string).map { case (code, item) => code.toLowerCase -> item.last }
      case _ => to[Bitpay](LNParams.connectionProvider.get("https://bitpay.com/rates").string).data.map { case BitpayItem(code, rate) => code.toLowerCase -> rate }.toMap
    }
  }

  def enrichFiats(fs: Tools.Fiat2Btc): Tools.Fiat2Btc = {
    val eur = fs.get("eur").getOrElse(0.0)
    val usd = fs.get("usd").getOrElse(0.0)
    val frf = 6.55957 * eur // Last known exchange rate 17 February 2002
    val richFiats = Map(
      "cym" -> eur,
      "lvl" -> 0.702804 * eur, // Last known exchange rate
      "dm" ->  1.95583 * eur, // Last known exchange rate 1 March 2002
      "frf" -> frf,
      "esd" -> usd,
      "svc" -> 8.75 * usd, // Last known exchange rate 2001
      "sps" -> (1.0 / 5.0) * frf, // 1 salvadoran peso = 5 francs 1919
      "eip" -> (1.0 / 1.2697) * eur // https://remitradar.com/IEP-to-EUR-best-exchange-rate
    )
    fs ++ richFiats
  }


  def updateInfo(newRates: Tools.Fiat2Btc): Unit = {
    info = FiatRatesInfo(newRates, info.rates, System.currentTimeMillis)
    for (lst <- listeners) lst.onFiatRates(info)
  }

  var listeners: Set[FiatRatesListener] = Set.empty
  var focused: Option[String] = None
  var info: FiatRatesInfo = bag.tryGetFiatRatesInfo getOrElse {
    FiatRatesInfo(rates = Map.empty, oldRates = Map.empty, stamp = 0L)
  }

  private[this] val periodSecs = 60 * 30
  private[this] val retryRepeatDelayedCall: Observable[Tools.Fiat2Btc] = {
    val retry = Rx.retry(Rx.ioQueue.map(_ => reloadData), Rx.incSec, 3 to 18 by 3)
    val repeat = Rx.repeat(retry, Rx.incSec, periodSecs to Int.MaxValue by periodSecs)
    Rx.initDelay(repeat, info.stamp, periodSecs * 1000L)
  }

  private[this] def updateRates(newRates: Tools.Fiat2Btc) = {
    info = FiatRatesInfo(enrichFiats(newRates), info.rates, System.currentTimeMillis)
    for (lst <- listeners) lst.onFiatRates(info)
  }

  val subscription: Subscription = retryRepeatDelayedCall.subscribe(updateRates, Tools.none)

  def updateNow: Unit = {
    var observable = Rx.retry(Rx.ioQueue.map(_ => reloadData), Rx.incSec, 3 to 18 by 3)
    observable.foreach(updateRates)
  }
}

trait FiatRatesListener {
  def onFiatRates(rates: FiatRatesInfo): Unit
}

case class CoinGeckoItem(value: Double)
case class BlockchainInfoItem(last: Double)
case class BitpayItem(code: String, rate: Double)

case class Bitpay(data: FiatRates.BitpayItemList)
case class CoinGecko(rates: FiatRates.CoinGeckoItemMap)

case class FiatRatesInfo(rates: Tools.Fiat2Btc, oldRates: Tools.Fiat2Btc, stamp: Long) {
  def pctDifference(code: String): Option[String] = List(rates get code, oldRates get code) match {
    case Some(fresh) :: Some(old) :: Nil if fresh > old => Some(s"<font color=#8BD670><small>▲</small> ${Denomination.formatFiatPrecise format pctChange(fresh, old).abs}%</font>")
    case Some(fresh) :: Some(old) :: Nil if fresh < old => Some(s"<small>▼</small> ${Denomination.formatFiatPrecise format pctChange(fresh, old).abs}%")
    case _ => None
  }

  def pctChange(fresh: Double, old: Double): Double = (fresh - old) / old * 100
}
