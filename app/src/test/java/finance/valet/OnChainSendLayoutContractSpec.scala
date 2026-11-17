package finance.valet

import org.junit.Assert._
import org.junit.Test
import org.w3c.dom.{Document, Element, Node}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import javax.xml.parsers.DocumentBuilderFactory
import java.util.regex.Pattern

class OnChainSendLayoutContractSpec {
  private val AndroidNs = "http://schemas.android.com/apk/res/android"

  private def findRepoRootFromCwdAncestry(start: Path): Option[Path] = {
    var current: Path = start

    while (current != null && !Files.isDirectory(current.resolve("app/src"))) {
      current = current.getParent
    }

    Option(current)
  }

  private lazy val repoRoot: Path =
    findRepoRootFromCwdAncestry(Paths.get("").toAbsolutePath.normalize).getOrElse {
      throw new IllegalStateException("Could not locate repo root containing app/src from current working directory ancestry")
    }

  private def read(path: String): String =
    Files.readString(repoRoot.resolve(path), StandardCharsets.UTF_8)

  private def parseXml(path: String): Document = {
    val factory = DocumentBuilderFactory.newInstance
    factory.setNamespaceAware(true)
    val builder = factory.newDocumentBuilder()
    builder.parse(repoRoot.resolve(path).toFile)
  }

  private def androidAttr(element: Element, name: String): String =
    if (element.hasAttributeNS(AndroidNs, name)) element.getAttributeNS(AndroidNs, name) else ""

  private def elementByAndroidId(document: Document, idValue: String): Element = {
    val all = document.getElementsByTagName("*")

    for (idx <- 0 until all.getLength) {
      val node = all.item(idx)
      if (node.isInstanceOf[Element]) {
        val element = node.asInstanceOf[Element]
        if (androidAttr(element, "id") == s"@+id/$idValue") return element
      }
    }

    throw new IllegalStateException(s"Element with android:id=@+id/$idValue not found")
  }

  private def directElementChildren(parent: Element): Vector[Element] = {
    val nodes = parent.getChildNodes
    val builder = Vector.newBuilder[Element]

    for (idx <- 0 until nodes.getLength) {
      val node = nodes.item(idx)
      if (node.getNodeType == Node.ELEMENT_NODE) builder += node.asInstanceOf[Element]
    }

    builder.result()
  }

  private def dimenValue(document: Document, name: String): String = {
    val all = document.getElementsByTagName("dimen")

    for (idx <- 0 until all.getLength) {
      val node = all.item(idx)
      if (node.isInstanceOf[Element]) {
        val element = node.asInstanceOf[Element]
        if (element.getAttribute("name") == name) return element.getTextContent.trim
      }
    }

    throw new IllegalStateException(s"dimen '$name' not found")
  }

  private def classBody(source: String, className: String): String = {
    val classStart = source.indexOf(s"class $className")
    if (classStart < 0) throw new IllegalStateException(s"Class '$className' not found")

    val nextClass = source.indexOf("\n  class ", classStart + 1)
    val classEnd = if (nextClass >= 0) nextClass else source.length
    source.substring(classStart, classEnd)
  }

  @Test
  def onChainConfirmDestinationUsesUniformSpacingResourceAndInsets: Unit = {
    val onChain = parseXml("app/src/main/res/layout/frag_input_on_chain.xml")
    val dimens = parseXml("app/src/main/res/values/dimens.xml")

    val caption = elementByAndroidId(onChain, "confirmDestinationCaption")
    val rows = elementByAndroidId(onChain, "confirmDestinationRows")

    assertEquals("15dp", androidAttr(caption, "layout_marginStart"))
    assertEquals("15dp", androidAttr(caption, "layout_marginEnd"))
    assertEquals("15dp", androidAttr(rows, "layout_marginStart"))
    assertEquals("15dp", androidAttr(rows, "layout_marginEnd"))

    assertEquals("@dimen/confirm_destination_spacing", androidAttr(rows, "layout_marginTop"))
    assertEquals("@dimen/confirm_destination_spacing", androidAttr(rows, "layout_marginBottom"))
    assertEquals("8dp", dimenValue(dimens, "confirm_destination_spacing"))

    val baseActivitySource = read("app/src/main/java/finance/valet/BaseActivity.scala")
    assertTrue(baseActivitySource.contains("getDimensionPixelSize(R.dimen.confirm_destination_spacing)"))
  }

  @Test
  def firstDialogKeepsNeutralSendHeadingAndConfirmUsesConfirmHeading: Unit = {
    val baseActivitySource = read("app/src/main/java/finance/valet/BaseActivity.scala")
    val hubSource = read("app/src/main/java/finance/valet/HubActivity.scala")
    val strings = parseXml("app/src/main/res/values/strings.xml")
    val titleLayout = parseXml("app/src/main/res/layout/frag_top_tip.xml")
    val styles = parseXml("app/src/main/res/values/styles.xml")

    // New first-dialog title helpers must be dedicated and not alter global TitleView callers.
    assertTrue(baseActivitySource.contains("def sendEditTitleViewFromUri(uri: BitcoinUri, walletLabel: String): TitleView"))
    assertTrue(baseActivitySource.contains("def sendEditTitleViewForBatch(walletLabel: String): TitleView"))
    assertTrue(baseActivitySource.contains("def configureDestinationSubtitle(rawDestination: String): Unit"))

    // First dialog must keep a neutral heading and preserve BIP21 metadata in the title caption.
    assertFalse(baseActivitySource.contains("sendTransactionInlineTitle(getString(dialog_send_transaction), walletLabel)"))
    assertTrue(baseActivitySource.contains("private def titleViewFromUri(uri: BitcoinUri, titleRes: Int): TitleView = {"))
    assertTrue(baseActivitySource.contains("sendTransactionTitleCaption(getString(titleRes), uri.label, uri.message)"))
    assertTrue(baseActivitySource.contains("def titleViewFromUri(uri: BitcoinUri): TitleView = titleViewFromUri(uri, dialog_send_transaction)"))
    assertTrue(baseActivitySource.contains("def confirmTitleViewFromUri(uri: BitcoinUri): TitleView = titleViewFromUri(uri, dialog_confirm_transaction)"))
    assertTrue(baseActivitySource.contains("val title = new TitleView(caption)"))
    assertTrue(baseActivitySource.contains("addFlowChip(title.flow, walletBadge, R.drawable.border_yellow)"))

    // Title layout must expose a dedicated subtitle line for destination address.
    val subtitle = elementByAndroidId(titleLayout, "tipSubtitle")
    assertEquals("gone", androidAttr(subtitle, "visibility"))
    assertEquals("@style/FillingView", subtitle.getAttribute("style"))

    // FillingView style must constrain width to enable middle ellipsis in dialog bounds.
    val styleNodes = styles.getElementsByTagName("style")
    val fillingView = (0 until styleNodes.getLength).map(styleNodes.item).collectFirst {
      case e: Element if e.getAttribute("name") == "FillingView" => e
    }.getOrElse(throw new IllegalStateException("Style 'FillingView' not found"))
    val fillingChildren = directElementChildren(fillingView)
    assertTrue(fillingChildren.exists(e => e.getAttribute("name") == "android:layout_width" && e.getTextContent.trim == "match_parent"))

    // Single-send and batch must use dedicated first-dialog helpers.
    assertTrue(hubSource.contains("val editTitle = sendEditTitleViewFromUri(uri, fromWallet.info.label)"))
    assertTrue(hubSource.contains("val editTitle = sendEditTitleViewForBatch(fromWallet.info.label)"))
    assertTrue(hubSource.contains("bringChainWalletChooser(me titleViewFromUri bitcoinUri)"))

    // Confirmation title must be explicit for single and batch dialogs; badge wiring stays intact.
    assertTrue(hubSource.contains("val confirmTitle = confirmTitleViewFromUri(uri)"))
    assertTrue(hubSource.contains("val confirmTitle = new TitleView(me getString dialog_confirm_transaction)"))
    assertTrue(hubSource.contains("addFlowChip(confirmTitle.flow, getString(dialog_send_btc_from).format(fromWallet.info.label), R.drawable.border_yellow)"))

    // Section switch callback binds title visibility to actual ChainSendView state transitions.
    assertTrue(baseActivitySource.contains("var onSectionSwitched: HasHostView => Unit = _"))
    assertTrue(baseActivitySource.contains("if (onSectionSwitched != null) onSectionSwitched(visibleSection)"))
    assertTrue(hubSource.contains("sendView.onSectionSwitched = section => syncTitle(section)"))
    assertTrue(hubSource.contains("val useEditTitle = section == sendView.chainEditView"))

    // Subtitle render path must keep middle ellipsis and full accessibility line from one localized source.
    assertTrue(baseActivitySource.contains("tipSubtitle.setEllipsize(TextUtils.TruncateAt.MIDDLE)"))
    assertTrue(baseActivitySource.contains("tipSubtitle.setContentDescription(fullLine)"))

    val stringNodes = strings.getElementsByTagName("string")
    val toPrefix = (0 until stringNodes.getLength).map(stringNodes.item).collectFirst {
      case e: Element if e.getAttribute("name") == "dialog_send_btc_to_prefix" => e.getTextContent.trim
    }.getOrElse(throw new IllegalStateException("string 'dialog_send_btc_to_prefix' not found"))
    assertEquals("to address", toPrefix)
  }

  @Test
  def sharedDividerDefaultsRemainVisibleAndHideScopeStaysInChainEditViewOnly: Unit = {
    val shared = parseXml("app/src/main/res/layout/frag_input_fiat_converter.xml")
    val onChain = parseXml("app/src/main/res/layout/frag_input_on_chain.xml")

    val sharedDivider = elementByAndroidId(shared, "fiatConverterBottomDivider")
    assertEquals("View", sharedDivider.getTagName)
    assertEquals("@style/GrayDivider", sharedDivider.getAttribute("style"))
    assertNotEquals("gone", androidAttr(sharedDivider, "visibility"))

    val editChain = elementByAndroidId(onChain, "editChain")
    val children = directElementChildren(editChain)

    val feeIncludeIndex = children.indexWhere { child =>
      child.getTagName == "include" && child.getAttribute("layout") == "@layout/frag_fees"
    }

    val postFeeDividerIndex = children.indexWhere { child =>
      child.getTagName == "View" && child.getAttribute("style") == "@style/GrayDivider"
    }

    assertTrue("Expected frag_fees include inside editChain", feeIncludeIndex >= 0)
    assertEquals("Expected GrayDivider immediately after frag_fees include", feeIncludeIndex + 1, postFeeDividerIndex)

    val baseActivitySource = read("app/src/main/java/finance/valet/BaseActivity.scala")
    val chainEditViewBody = classBody(baseActivitySource, "ChainEditView")
    val hideCall = "setVis(isVisible = false, fiatConverterBottomDivider)"

    assertTrue(chainEditViewBody.contains("val fiatConverterBottomDivider: View = inputChain.findViewById(R.id.fiatConverterBottomDivider)"))
    assertTrue(chainEditViewBody.contains(hideCall))
    assertEquals(1, Pattern.quote(hideCall).r.findAllIn(baseActivitySource).length)
    assertTrue(baseActivitySource.contains("row.setBackgroundResource(R.drawable.confirm_destination_block_bg)"))
    assertTrue(baseActivitySource.contains("val blockPadding = (density * 10).toInt"))
  }
}
