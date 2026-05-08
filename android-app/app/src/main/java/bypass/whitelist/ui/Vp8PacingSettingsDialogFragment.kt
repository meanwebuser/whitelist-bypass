package bypass.whitelist.ui

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import bypass.whitelist.R
import bypass.whitelist.util.Prefs
import bypass.whitelist.util.VP8Defaults

class Vp8PacingSettingsDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = layoutInflater.inflate(R.layout.dialog_vp8_pacing_settings, null)

        val enabledCheckbox = view.findViewById<CheckBox>(R.id.vp8PacingEnabledCheckbox)
        val inputsContainer = view.findViewById<LinearLayout>(R.id.vp8PacingInputs)
        val fpsInput = view.findViewById<EditText>(R.id.vp8FpsInput)
        val batchInput = view.findViewById<EditText>(R.id.vp8BatchInput)

        enabledCheckbox.isChecked = Prefs.vp8PacingEnabled
        inputsContainer.visibility = if (Prefs.vp8PacingEnabled) View.VISIBLE else View.GONE
        fpsInput.setText(Prefs.vp8Fps.toString())
        batchInput.setText(Prefs.vp8Batch.toString())

        enabledCheckbox.setOnCheckedChangeListener { _, checked ->
            inputsContainer.visibility = if (checked) View.VISIBLE else View.GONE
        }

        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.vp8_pacing_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Prefs.vp8PacingEnabled = enabledCheckbox.isChecked
                if (enabledCheckbox.isChecked) {
                    val fps = fpsInput.text.toString().toIntOrNull()
                    if (fps != null && fps in 1..240) {
                        Prefs.vp8Fps = fps
                    }
                    val batch = batchInput.text.toString().toIntOrNull()
                    if (batch != null && batch in 1..256) {
                        Prefs.vp8Batch = batch
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    companion object {
        const val TAG = "Vp8PacingSettingsDialog"

        fun defaults(): Pair<Int, Int> = VP8Defaults.FPS to VP8Defaults.BATCH
    }
}
