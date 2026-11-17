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
