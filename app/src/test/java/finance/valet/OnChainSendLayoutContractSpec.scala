package finance.valet

import org.junit.Assert._
import org.junit.Test

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.xml.{Elem, XML}

class OnChainSendLayoutContractSpec {
  private val repoRoot = Paths.get("/opt/data/workspace/t_3f9e3ff6-valet-impl")

  private def xml(path: String): Elem = XML.loadFile(repoRoot.resolve(path).toFile)

  private def attr(node: scala.xml.Node, key: String): String = node.attribute("android", key).map(_.text).getOrElse("")

  @Test
  def onChainConfirmDestinationUsesUniformCaptionToFirstBoxGapAndKeepsInsets: Unit = {
    val onChain = xml("app/src/main/res/layout/frag_input_on_chain.xml")

    val caption = (onChain \\ "TextView").find(node => attr(node, "id") == "@+id/confirmDestinationCaption").get
    val rows = (onChain \\ "LinearLayout").find(node => attr(node, "id") == "@+id/confirmDestinationRows").get

    assertEquals("15dp", attr(caption, "layout_marginStart"))
    assertEquals("15dp", attr(caption, "layout_marginEnd"))
    assertEquals("15dp", attr(rows, "layout_marginStart"))
    assertEquals("15dp", attr(rows, "layout_marginEnd"))

    assertEquals("@dimen/confirm_destination_spacing", attr(rows, "layout_marginTop"))
    assertEquals("@dimen/confirm_destination_spacing", attr(rows, "layout_marginBottom"))
  }

  @Test
  def sharedFiatConverterDividerIsAddressableAndHiddenOnlyInOnChainRenderer: Unit = {
    val shared = xml("app/src/main/res/layout/frag_input_fiat_converter.xml")

    val divider = (shared \\ "View").find { node =>
      attr(node, "style") == "@style/GrayDivider"
    }.get
    assertEquals("@+id/fiatConverterBottomDivider", attr(divider, "id"))

    val baseActivitySource = Files.readString(
      repoRoot.resolve("app/src/main/java/finance/valet/BaseActivity.scala"),
      StandardCharsets.UTF_8
    )

    assertTrue(baseActivitySource.contains("R.id.fiatConverterBottomDivider"))
    assertTrue(baseActivitySource.contains("setVis(isVisible = false, fiatConverterBottomDivider)"))
  }
}
