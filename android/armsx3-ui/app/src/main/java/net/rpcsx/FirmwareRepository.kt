package net.rpcsx

import android.content.res.Resources.NotFoundException
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.annotation.Keep
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File


enum class FirmwareStatus {
    None,
    Installed,
    Compiled
}

@Serializable
private data class FirmwareInfo(val version: String?, val status: FirmwareStatus)

class FirmwareRepository {
    companion object {
        val progressChannel: MutableState<Long?> = mutableStateOf(null)
        val version: MutableState<String?> = mutableStateOf(null)
        val status: MutableState<FirmwareStatus> = mutableStateOf(FirmwareStatus.None)

        fun save() {
                try {
                    File(RPCSX.rootDirectory + "fw.json").writeText(
                        Json.encodeToString(
                            FirmwareInfo(version.value, status.value)
                        )
                    )
                } catch (e: Exception) {
                    e.printStackTrace()

            }
        }

        fun load() {
                // No fw.json is the normal first-run state, not an error. Reading it blind
                // threw FileNotFoundException and printStackTrace() put the whole trace in
                // the diagnostic log, where it reads as a crash -- it was reported as one.
                val file = File(RPCSX.rootDirectory + "fw.json")
                if (!file.isFile) return
                try {
                    val info = Json.decodeFromString<FirmwareInfo>(file.readText())
                    status.value = info.status
                    version.value = info.version
                } catch (_: NotFoundException) {
                } catch (e: Exception) {
                    e.printStackTrace()
                }
        }

        @Keep
        @JvmStatic
        fun onFirmwareInstalled(version: String?) {
            updateStatus(version, FirmwareStatus.Installed)
        }

        @Keep
        @JvmStatic
        fun onFirmwareCompiled(version: String?) {
            updateStatus(version, FirmwareStatus.Compiled)
        }

        fun updateStatus(version: String?, status: FirmwareStatus) {
            synchronized(Companion.version) {
                Companion.version.value = version
                Companion.status.value = status

                save()
            }
        }
    }
}
