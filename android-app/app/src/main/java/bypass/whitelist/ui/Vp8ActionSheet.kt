package bypass.whitelist.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.fragment.app.FragmentManager
import bypass.whitelist.R
import bypass.whitelist.util.Callback
import bypass.whitelist.util.Prefs
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

class Vp8ActionSheet : BottomSheetDialogFragment() {

    private var onSaved: Callback? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_action_vp8, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val fps = view.findViewById<EditText>(R.id.vp8FpsInput)
        val batch = view.findViewById<EditText>(R.id.vp8BatchInput)
        val dualTrack = view.findViewById<MaterialSwitch>(R.id.vp8DualTrackSwitch)
        fps.setText(Prefs.vp8Fps.toString())
        batch.setText(Prefs.vp8Batch.toString())
        dualTrack.isChecked = Prefs.dualTrack

        view.findViewById<MaterialButton>(R.id.vp8CancelButton).setOnClickListener { dismiss() }
        view.findViewById<MaterialButton>(R.id.vp8SaveButton).setOnClickListener {
            fps.text.toString().toIntOrNull()?.takeIf { it in 1..240 }?.let { Prefs.vp8Fps = it }
            batch.text.toString().toIntOrNull()?.takeIf { it in 1..256 }?.let { Prefs.vp8Batch = it }
            Prefs.dualTrack = dualTrack.isChecked
            onSaved?.invoke()
            dismiss()
        }
    }

    companion object {
        fun show(manager: FragmentManager, onSaved: Callback) {
            Vp8ActionSheet().apply { this.onSaved = onSaved }.show(manager, "Vp8ActionSheet")
        }
    }
}
