// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import helium314.keyboard.keyboard.VarneleTouchModel
import helium314.keyboard.latin.inputlogic.VarneleTapLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.inputlogic.VarneleUserLm
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsWithoutKey
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.SwitchPreference

/** Settings of Varnelė, the Lithuanian checker: only this fork has them. */
@Composable
fun VarneleScreen(
    onClickBack: () -> Unit,
) {
    val prefs = LocalContext.current.prefs()
    val b = (LocalContext.current.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    val autocorrectEnabled = prefs.getBoolean(Settings.PREF_AUTO_CORRECTION, Defaults.PREF_AUTO_CORRECTION)
    val items = listOf(
        R.string.settings_category_correction,
        if (autocorrectEnabled) Settings.PREF_VARNELE_KEY_SHIFT else null,
        Settings.PREF_VARNELE_FIX_EARLIER,
        Settings.PREF_VARNELE_COMMAS,
        Settings.PREF_VARNELE_UNDERLINE,
        Settings.PREF_VARNELE_FORMAL,
        R.string.settings_category_varnele_predictions,
        Settings.PREF_VARNELE_GHOST_NEXT,
        Settings.PREF_VARNELE_LEARN,
        SettingsWithoutKey.VARNELE_FORGET,
        R.string.settings_category_varnele_accuracy,
        Settings.PREF_VARNELE_TOUCH_MODEL,
        SettingsWithoutKey.VARNELE_TOUCH_FORGET,
        Settings.PREF_VARNELE_TAP_LOG,
        SettingsWithoutKey.VARNELE_TAP_LOG_EXPORT,
        SettingsWithoutKey.VARNELE_TAP_LOG_DELETE,
    )
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_varnele),
        settings = items
    )
}

fun createVarneleSettings(context: Context) = listOf(
    Setting(context, Settings.PREF_VARNELE_KEY_SHIFT,
        R.string.varnele_key_shift, R.string.varnele_key_shift_summary
    ) {
        SwitchPreference(it, Defaults.PREF_VARNELE_KEY_SHIFT)
    },
    Setting(context, Settings.PREF_VARNELE_FIX_EARLIER,
        R.string.varnele_fix_earlier, R.string.varnele_fix_earlier_summary
    ) {
        SwitchPreference(it, Defaults.PREF_VARNELE_FIX_EARLIER)
    },
    Setting(context, Settings.PREF_VARNELE_COMMAS,
        R.string.varnele_commas, R.string.varnele_commas_summary
    ) {
        SwitchPreference(it, Defaults.PREF_VARNELE_COMMAS)
    },
    Setting(context, Settings.PREF_VARNELE_UNDERLINE,
        R.string.varnele_underline, R.string.varnele_underline_summary
    ) {
        SwitchPreference(it, Defaults.PREF_VARNELE_UNDERLINE)
    },
    Setting(context, Settings.PREF_VARNELE_FORMAL,
        R.string.varnele_formal, R.string.varnele_formal_summary
    ) {
        SwitchPreference(it, Defaults.PREF_VARNELE_FORMAL)
    },
    Setting(context, Settings.PREF_VARNELE_GHOST_NEXT,
        R.string.varnele_ghost_next, R.string.varnele_ghost_next_summary
    ) {
        SwitchPreference(it, Defaults.PREF_VARNELE_GHOST_NEXT)
    },
    Setting(context, Settings.PREF_VARNELE_LEARN,
        R.string.varnele_learn, R.string.varnele_learn_summary
    ) {
        SwitchPreference(it, Defaults.PREF_VARNELE_LEARN)
    },
    Setting(context, SettingsWithoutKey.VARNELE_FORGET,
        R.string.varnele_forget, R.string.varnele_forget_summary
    ) { setting ->
        var showConfirmDialog by rememberSaveable { mutableStateOf(false) }
        Preference(
            name = setting.title,
            description = setting.description,
            onClick = { showConfirmDialog = true },
        )
        if (showConfirmDialog) {
            ConfirmationDialog(
                onDismissRequest = { showConfirmDialog = false },
                onConfirmed = { VarneleUserLm.forget() },
                content = { Text(stringResource(R.string.varnele_forget_confirm)) },
                confirmButtonText = stringResource(R.string.varnele_forget_button),
            )
        }
    },
    Setting(context, Settings.PREF_VARNELE_TOUCH_MODEL,
        R.string.varnele_touch_model, R.string.varnele_touch_model_summary
    ) {
        SwitchPreference(it, Defaults.PREF_VARNELE_TOUCH_MODEL)
    },
    Setting(context, SettingsWithoutKey.VARNELE_TOUCH_FORGET,
        R.string.varnele_touch_forget, R.string.varnele_touch_forget_summary
    ) { setting ->
        val ctx = LocalContext.current
        var showConfirmDialog by rememberSaveable { mutableStateOf(false) }
        Preference(
            name = setting.title,
            description = setting.description,
            onClick = { showConfirmDialog = true },
        )
        if (showConfirmDialog) {
            ConfirmationDialog(
                onDismissRequest = { showConfirmDialog = false },
                onConfirmed = { VarneleTouchModel.forget(ctx.filesDir) },
                content = { Text(stringResource(R.string.varnele_touch_forget_confirm)) },
                confirmButtonText = stringResource(R.string.varnele_forget_button),
            )
        }
    },
    Setting(context, Settings.PREF_VARNELE_TAP_LOG,
        R.string.varnele_tap_log, R.string.varnele_tap_log_summary
    ) {
        SwitchPreference(it, Defaults.PREF_VARNELE_TAP_LOG)
    },
    Setting(context, SettingsWithoutKey.VARNELE_TAP_LOG_EXPORT, R.string.varnele_tap_log_export) { setting ->
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        var count by remember { mutableIntStateOf(-1) }
        LaunchedEffect(Unit) { count = withContext(Dispatchers.IO) { VarneleTapLog.count(ctx) } }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
            val uri = result.data?.data ?: return@rememberLauncherForActivityResult
            scope.launch(Dispatchers.IO) {
                ctx.contentResolver.openOutputStream(uri)?.use { VarneleTapLog.export(ctx, it) }
            }
        }
        Preference(
            name = setting.title,
            description = if (count > 0) stringResource(R.string.varnele_tap_log_export_summary, count)
                else stringResource(R.string.varnele_tap_log_none),
            onClick = {
                if (count <= 0) return@Preference
                val date = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Calendar.getInstance().time)
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/tab-separated-values")
                    .putExtra(Intent.EXTRA_TITLE, "varnele_taps_$date.tsv")
                launcher.launch(intent)
            },
        )
    },
    Setting(context, SettingsWithoutKey.VARNELE_TAP_LOG_DELETE, R.string.varnele_tap_log_delete) { setting ->
        val ctx = LocalContext.current
        var showConfirmDialog by rememberSaveable { mutableStateOf(false) }
        Preference(
            name = setting.title,
            onClick = { showConfirmDialog = true },
        )
        if (showConfirmDialog) {
            ConfirmationDialog(
                onDismissRequest = { showConfirmDialog = false },
                onConfirmed = { VarneleTapLog.delete(ctx) },
                content = { Text(stringResource(R.string.varnele_tap_log_delete_confirm)) },
                confirmButtonText = stringResource(R.string.varnele_tap_log_delete_button),
            )
        }
    },
)

@Preview
@Composable
private fun PreferencePreview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            VarneleScreen { }
        }
    }
}
