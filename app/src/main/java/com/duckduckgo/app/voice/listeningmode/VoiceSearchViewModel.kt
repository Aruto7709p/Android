/*
 * Copyright (c) 2022 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.app.voice.listeningmode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duckduckgo.app.global.plugins.view_model.ViewModelFactoryPlugin
import com.duckduckgo.app.voice.listeningmode.OnDeviceSpeechRecognizer.Event.PartialResultReceived
import com.duckduckgo.app.voice.listeningmode.OnDeviceSpeechRecognizer.Event.RecognitionSuccess
import com.duckduckgo.app.voice.listeningmode.OnDeviceSpeechRecognizer.Event.VolumeUpdateReceived
import com.duckduckgo.app.voice.listeningmode.VoiceSearchViewModel.Command.HandleSpeechRecognitionSuccess
import com.duckduckgo.app.voice.listeningmode.VoiceSearchViewModel.Command.UpdateVoiceIndicator
import com.duckduckgo.di.scopes.AppScope
import com.squareup.anvil.annotations.ContributesMultibinding
import kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider

class VoiceSearchViewModel constructor(
    private val speechRecognizer: OnDeviceSpeechRecognizer
) : ViewModel() {
    data class ViewState(
        val result: String = "",
        val unsentResult: String = ""
    )

    sealed class Command {
        data class UpdateVoiceIndicator(val volume: Float) : Command()
        data class HandleSpeechRecognitionSuccess(val result: String) : Command()
    }

    private val viewState = MutableStateFlow(ViewState())

    private val command = Channel<Command>(1, DROP_OLDEST)

    fun viewState(): StateFlow<ViewState> {
        return viewState
    }

    fun commands(): Flow<Command> {
        return command.receiveAsFlow()
    }

    fun start() {
        if (viewState.value.result.isNotEmpty()) {
            viewModelScope.launch {
                viewState.emit(
                    viewState.value.copy(unsentResult = viewState.value.result)
                )
            }
        }

        speechRecognizer.start {
            when (it) {
                is PartialResultReceived -> showRecognizedSpeech(it.partialResult)
                is RecognitionSuccess -> sendCommand(
                    HandleSpeechRecognitionSuccess(
                        getFullResult(
                            it.result,
                            viewState.value.unsentResult
                        )
                    )
                )
                is VolumeUpdateReceived -> sendCommand(UpdateVoiceIndicator(it.normalizedVolume))
            }
        }
    }

    fun stop() {
        speechRecognizer.stop()
    }

    private fun sendCommand(commandToSend: Command) {
        viewModelScope.launch {
            command.send(commandToSend)
        }
    }

    private fun showRecognizedSpeech(result: String) {
        viewModelScope.launch {
            viewState.emit(
                viewState.value.copy(
                    result = getFullResult(result, viewState.value.unsentResult)
                )
            )
        }
    }

    private fun getFullResult(
        result: String,
        unsentResult: String
    ): String {
        return if (unsentResult.isNotEmpty()) {
            "$unsentResult $result"
        } else {
            result
        }
    }
}

@ContributesMultibinding(AppScope::class)
class VoiceSearchViewModelFactory @Inject constructor(
    private val speechRecognizer: Provider<OnDeviceSpeechRecognizer>
) : ViewModelFactoryPlugin {
    override fun <T : ViewModel?> create(modelClass: Class<T>): T? {
        with(modelClass) {
            return when {
                isAssignableFrom(VoiceSearchViewModel::class.java) -> (
                    VoiceSearchViewModel(speechRecognizer.get()) as T
                    )
                else -> null
            }
        }
    }
}