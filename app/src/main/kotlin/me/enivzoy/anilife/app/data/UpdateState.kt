package me.enivzoy.anilife.app.data

sealed interface UpdateState {
    data object Idle : UpdateState
    data class Downloading(val progress: Float?) : UpdateState
    data object Installing : UpdateState
    data class Failed(val message: String) : UpdateState

    val busy: Boolean
        get() = this is Downloading || this is Installing
}
