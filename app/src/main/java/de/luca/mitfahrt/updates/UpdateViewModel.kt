package de.luca.mitfahrt.updates

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.luca.mitfahrt.BuildConfig
import de.luca.mitfahrt.data.AppSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

enum class UpdatePhase { IDLE, CHECKING, CURRENT, EMPTY, AVAILABLE, DOWNLOADING, READY, ERROR }
data class UpdateState(val phase: UpdatePhase = UpdatePhase.IDLE, val info: UpdateInfo? = null,
    val progress: Float = 0f, val file: File? = null, val message: String? = null, val changes: String? = null)

class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val client = UpdateClient(application)
    private val settings = AppSettingsStore(application)
    private val checks = application.getSharedPreferences("update_checks", android.content.Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()

    override fun onCleared() {
        client.close(); settings.close()
        super.onCleared()
    }

    fun checkOnOpen() {
        UpdateScheduler.schedule(getApplication())
        val mode = settings.current().string("update_check", "open")
        val last = checks.getLong("last_check", 0L)
        val now = System.currentTimeMillis()
        if (UpdateCheckPolicy.shouldCheck(mode, last, now)) check()
    }

    fun check() {
        if (mutable.value.phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING)) return
        mutable.value = UpdateState(phase = UpdatePhase.CHECKING)
        viewModelScope.launch {
            try {
                val info = client.check()
                checks.edit().putLong("last_check", System.currentTimeMillis()).apply()
                val available = info.versionCode > BuildConfig.VERSION_CODE
                val changes = if (available) try { client.changes(info) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } else null
                mutable.value = UpdateState(if (available) UpdatePhase.AVAILABLE else UpdatePhase.CURRENT, info, changes = changes)
            } catch (e: CancellationException) { throw e
            } catch (_: NoPublishedUpdate) { mutable.value = UpdateState(UpdatePhase.EMPTY)
            } catch (e: Exception) { mutable.value = UpdateState(UpdatePhase.ERROR, message = e.message ?: "Update-Suche fehlgeschlagen.") }
        }
    }

    fun download() {
        val state = mutable.value
        val info = state.info ?: return
        if (state.phase !in setOf(UpdatePhase.AVAILABLE, UpdatePhase.ERROR)) return
        mutable.value = state.copy(phase = UpdatePhase.DOWNLOADING, progress = 0f, message = null)
        viewModelScope.launch {
            try {
                val file = client.download(info) { progress -> mutable.value = mutable.value.copy(progress = progress) }
                mutable.value = UpdateState(UpdatePhase.READY, info, progress = 1f, file = file, changes = state.changes)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { mutable.value = UpdateState(UpdatePhase.ERROR, info, message = e.message ?: "Download fehlgeschlagen.", changes = state.changes) }
        }
    }
}
