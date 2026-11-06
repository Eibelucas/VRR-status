package de.luca.mitfahrt.updates

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.luca.mitfahrt.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

enum class UpdatePhase { IDLE, CHECKING, CURRENT, EMPTY, AVAILABLE, DOWNLOADING, READY, ERROR }
data class UpdateState(val phase: UpdatePhase = UpdatePhase.IDLE, val info: UpdateInfo? = null,
    val progress: Float = 0f, val file: File? = null, val message: String? = null)

class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val client = UpdateClient(application)
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()

    fun check() {
        if (mutable.value.phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING)) return
        mutable.value = UpdateState(phase = UpdatePhase.CHECKING)
        viewModelScope.launch {
            try {
                val info = client.check()
                mutable.value = UpdateState(if (info.versionCode > BuildConfig.VERSION_CODE) UpdatePhase.AVAILABLE else UpdatePhase.CURRENT, info)
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
                mutable.value = UpdateState(UpdatePhase.READY, info, progress = 1f, file = file)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { mutable.value = UpdateState(UpdatePhase.ERROR, info, message = e.message ?: "Download fehlgeschlagen.") }
        }
    }
}
