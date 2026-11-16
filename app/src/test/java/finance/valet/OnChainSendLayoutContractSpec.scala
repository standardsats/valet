package finance.valet

import org.junit.Assert._
import org.junit.Test

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

class OnChainSendLayoutContractSpec {
  private val repoRoot = Paths.get("/opt/data/workspace/t_3f9e3ff6-valet-impl")

  private def read(path: String): String = Files.readString(repoRoot.resolve(path), StandardCharsets.UTF_8)

  @Test
  def onChainConfirmDestinationUsesUniformCaptionToFirstBoxGapAndKeepsInsets: Unit = {
    val onChain = read("app/src/main/res/layout/frag_input_on_chain.xml")

    assertTrue(onChain.contains("android:id=\"@+id/confirmDestinationCaption\""))
    assertTrue(onChain.contains("android:layout_marginStart=\"15dp\""))
    assertTrue(onChain.contains("android:layout_marginEnd=\"15dp\""))

    assertTrue(onChain.contains("android:id=\"@+id/confirmDestinationRows\""))
    assertTrue(onChain.contains("android:layout_marginTop=\"@dimen/confirm_destination_spacing\""))
    assertTrue(onChain.contains("android:layout_marginBottom=\"@dimen/confirm_destination_spacing\""))
  }

  @Test
  def sharedFiatConverterDividerIsAddressableAndHiddenOnlyInOnChainRenderer: Unit = {
    val shared = read("app/src/main/res/layout/frag_input_fiat_converter.xml")
    assertTrue(shared.contains("android:id=\"@+id/fiatConverterBottomDivider\""))

    val baseActivitySource = read("app/src/main/java/finance/valet/BaseActivity.scala")
    assertTrue(baseActivitySource.contains("R.id.fiatConverterBottomDivider"))
    assertTrue(baseActivitySource.contains("setVis(isVisible = false, fiatConverterBottomDivider)"))
  }
}
