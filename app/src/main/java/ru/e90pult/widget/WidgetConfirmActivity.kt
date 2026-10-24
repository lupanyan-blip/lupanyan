package ru.e90pult.widget

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.e90pult.App
import ru.e90pult.ui.E90Theme
import ru.e90pult.ui.Pal

/** Окно подтверждения управляющей команды с виджета. */
class WidgetConfirmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val code = intent?.getStringExtra(WidgetUpdater.EXTRA_CODE)
        if (code == null) {
            finish()
            return
        }
        val g = App.graph(this)
        val appContext = applicationContext
        setContent {
            E90Theme {
                var sending by rememberSaveable { mutableStateOf(false) }
                val title by produceState(code) {
                    val pid = g.prefs.value.activeProfileId
                    val p = g.db.profiles().get(pid) ?: g.db.profiles().allNow().firstOrNull()
                    value = g.commands.find(p, code)?.let { "${it.name} ($code)" } ?: code
                }
                AlertDialog(
                    onDismissRequest = { finish() },
                    containerColor = Pal.Surface,
                    title = { Text("Отправить команду?") },
                    text = { Text("$title\n\nС виджета: SMS уйдёт на номер сигнализации.") },
                    confirmButton = {
                        TextButton(enabled = !sending, onClick = {
                            sending = true
                            g.scope.launch {
                                val msg = WidgetActionReceiver.sendFromWidget(appContext, code)
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(appContext, msg, Toast.LENGTH_LONG).show()
                                    finish()
                                }
                            }
                        }) { Text("Отправить") }
                    },
                    dismissButton = { TextButton(onClick = { finish() }) { Text("Отмена") } }
                )
            }
        }
    }
}
