package com.communicator.ui.webrtc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.communicator.app
import com.communicator.ui.theme.Theme
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer

@Composable
fun WebRtcCallScreen(
    webrtcRepository: app.WebRtcRepository,
    onBack: () -> Unit
) {
    val activeCall = webrtcRepository.getActiveCall()
    val activeCalls = webrtcRepository.getCalls().collectAsStateWithLifecycle()

    androidx.compose.foundation.layout.Column(
        modifier = androidx.compose.ui.Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        androidx.compose.material3.TopAppBar(
            title = { androidx.compose.material3.Text(text = "WebRTC Call", fontSize = 18.sp) },
            navigationIcon = {
                androidx.compose.material3.IconButton(onClick = onBack) {
                    androidx.compose.material.Icon(
                        imageVector = androidx.compose.material.Icons.Default.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            }
        )

        // Active call or dialer
        if (activeCall != null) {
            // Active call UI
            Card(modifier = androidx.compose.ui.Modifier.fillMaxWidth()) {
                androidx.compose.foundation.layout.Column(modifier = androidx.compose.ui.Modifier.padding(16.dp)) {
                    androidx.compose.material3.Text(
                        text = activeCall.remoteId ?: "Unknown",
                        fontSize = 24.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )

                    androidx.compose.material3.Text(
                        text = activeCall.state.name,
                        fontSize = 14.sp,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Video display
                    if (activeCall.isVideo) {
                        androidx.compose.foundation.layout.Box(
                            modifier = androidx.compose.ui.Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .padding(top = 16.dp)
                        ) {
                            // Remote video
                            // androidx.compose.ui.platform.AndroidView(
                            //     factory = { context ->
                            //         val surfaceView = SurfaceViewRenderer(context)
                            //         surfaceView.init(EglBase.create().eglBaseContext, null)
                            //         surfaceView.setMirror(false)
                            //         surfaceView.setEnableHardwareScaler(true)
                            //         activeCall.remoteVideoTrack?.addSink(surfaceView)
                            //         surfaceView
                            //     },
                            //     modifier = androidx.compose.ui.Modifier.fillMaxSize()
                            // )
                            
                            androidx.compose.material3.Text(
                                text = "Remote Video (SurfaceViewRenderer needed)",
                                modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                                textAlign = androidx.compose.ui.text.TextAlign.Center
                            )
                        }

                        // Local preview
                        androidx.compose.foundation.layout.Box(
                            modifier = androidx.compose.ui.Modifier
                                .size(120.dp)
                                .padding(16.dp)
                                .align(Alignment.TopEnd)
                        ) {
                            // androidx.compose.ui.platform.AndroidView(
                            //     factory = { context ->
                            //         val surfaceView = SurfaceViewRenderer(context)
                            //         surfaceView.init(EglBase.create().eglBaseContext, null)
                            //         surfaceView.setMirror(true)
                            //         surfaceView.setEnableHardwareScaler(true)
                            //         activeCall.localVideoTrack?.addSink(surfaceView)
                            //         surfaceView
                            //     },
                            //     modifier = androidx.compose.ui.Modifier.fillMaxSize()
                            // )
                            
                            androidx.compose.material3.Text(
                                text = "Local Preview",
                                modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                                textAlign = androidx.compose.ui.text.TextAlign.Center,
                                fontSize = 12.sp
                            )
                        }
                    }

                    androidx.compose.foundation.layout.Row(
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        androidx.compose.material3.Button(
                            onClick = { webrtcRepository.holdCall(activeCall.callId) },
                            modifier = androidx.compose.ui.Modifier.weight(1f),
                            enabled = !activeCall.isOnHold
                        ) {
                            androidx.compose.material3.Text(if (activeCall.isOnHold) "Unhold" else "Hold")
                        }

                        androidx.compose.material3.OutlinedButton(
                            onClick = { webrtcRepository.muteCall(activeCall.callId, !activeCall.isMuted) },
                            modifier = androidx.compose.ui.Modifier.weight(1f)
                        ) {
                            androidx.compose.material3.Text(if (activeCall.isMuted) "Unmute" else "Mute")
                        }

                        androidx.compose.material3.OutlinedButton(
                            onClick = { /* speaker toggle */ },
                            modifier = androidx.compose.ui.Modifier.weight(1f)
                        ) {
                            androidx.compose.material3.Text(if (activeCall.isSpeaker) "Earpiece" else "Speaker")
                        }

                        androidx.compose.material3.OutlinedButton(
                            onClick = { /* switch camera */ },
                            modifier = androidx.compose.ui.Modifier.weight(1f),
                            enabled = activeCall.isVideo
                        ) {
                            androidx.compose.material3.Text("Switch Cam")
                        }
                    }

                    Button(
                        onClick = { webrtcRepository.endCall(activeCall.callId) },
                        modifier = androidx.compose.ui.Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        androidx.compose.material3.Text("End Call", fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                }
            }
        } else {
            // Dialer
            var number by remember { mutableStateOf("") }
            var isVideoCall by remember { mutableStateOf(false) }

            androidx.compose.material3.TextField(
                value = number,
                onValueChange = { number = it.filter { it.isDigit() || it in "+*#" } },
                modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                label = { androidx.compose.material3.Text("Enter WebRTC Room/ID") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Text),
                visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None
            )

            // Video call toggle
            androidx.compose.material3.Switch(
                checked = isVideoCall,
                onCheckedChange = { isVideoCall = it },
                modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                androidx.compose.material3.Text("Video Call", fontSize = 16.sp)
            }

            // ICE servers input
            var iceServersText by remember { mutableStateOf("stun:stun.l.google.com:19302") }
            androidx.compose.material3.TextField(
                value = iceServersText,
                onValueChange = { iceServersText = it },
                modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { androidx.compose.material3.Text("ICE Servers (comma-separated)") },
                singleLine = true
            )

            // Room/Call ID input
            var roomId by remember { mutableStateOf("") }
            androidx.compose.material3.TextField(
                value = roomId,
                onValueChange = { roomId = it },
                modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { androidx.compose.material3.Text("Room ID (optional for peer-to-peer)") },
                singleLine = true
            )

            // Action buttons
            androidx.compose.foundation.layout.Row(
                modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (number.isNotBlank()) {
                            val iceServers = iceServersText.split(",").map { it.trim() }
                            if (isVideoCall) {
                                webrtcRepository.makeVideoCall(number, iceServers, roomId.ifBlank { null })
                            } else {
                                webrtcRepository.makeCall(number, iceServers, roomId.ifBlank { null })
                            }
                            number = ""
                        }
                    },
                    modifier = androidx.compose.ui.Modifier
                        .weight(1f)
                        .height(56.dp),
                    enabled = number.isNotBlank()
                ) {
                    androidx.compose.material3.Text(text = if (isVideoCall) "Video Call" else "Call", fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }

                androidx.compose.material3.OutlinedButton(
                    onClick = { number = "" },
                    modifier = androidx.compose.ui.Modifier
                        .weight(1f)
                        .height(56.dp)
                ) {
                    androidx.compose.material3.Text(text = "Clear", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
