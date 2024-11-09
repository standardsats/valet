package finance.valet.sheets

import android.view.{LayoutInflater, View, ViewGroup}
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import android.widget.ListView
import android.os.Bundle
import finance.valet.ChoiceReceiver
import finance.valet.utils.OnListItemClickListener


class BaseChoiceBottomSheet(list: ListView) extends BottomSheetDialogFragment { me =>
  override def onCreateView(inflater: LayoutInflater, container: ViewGroup, state: Bundle): View = list
}

class ChoiceBottomSheet(list: ListView, tag: AnyRef, host: ChoiceReceiver) extends BaseChoiceBottomSheet(list) { me =>
  override def onViewCreated(view: View, state: Bundle): Unit =
    list setOnItemClickListener new OnListItemClickListener {
      def onItemClicked(itemPosition: Int): Unit = {
        host.onChoiceMade(tag, itemPosition)
        dismiss
      }
    }
}
