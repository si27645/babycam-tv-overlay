package com.babycam.overlay

import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID

/**
 * Thin wrapper around Eclipse Paho's MQTT client for the doorbell trigger. This app only ever
 * subscribes - it never publishes anything of its own, and never runs a broker itself. Point it
 * at whatever broker a Home Assistant setup already has (commonly Mosquitto), and have an HA
 * automation publish to the configured topic when the doorbell fires; any message on that topic
 * counts as a trigger, the payload is ignored.
 *
 * All callbacks ([onTriggered], [onConnected], [onConnectFailed]) fire on Paho's own callback
 * thread, not the main thread - callers must post to a main-thread Handler themselves before
 * touching any Views.
 */
class MqttDoorbellClient(
    private val host: String,
    private val port: Int,
    private val useTls: Boolean,
    private val username: String,
    private val password: String,
    private val topic: String,
    private val onTriggered: () -> Unit
) {
    private var client: MqttAsyncClient? = null

    val isConfigured: Boolean
        get() = host.isNotBlank() && topic.isNotBlank()

    /**
     * Connects and subscribes. [onConnected]/[onConnectFailed] are optional and only really
     * useful for a one-shot "Test connection" check - the long-lived doorbell client the
     * service runs doesn't need them, since Paho's automatic reconnect handles the rest.
     */
    fun start(onConnected: (() -> Unit)? = null, onConnectFailed: ((String) -> Unit)? = null) {
        if (!isConfigured) {
            onConnectFailed?.invoke("No broker host/topic configured")
            return
        }
        stop()

        val scheme = if (useTls) "ssl" else "tcp"
        val brokerUrl = "$scheme://$host:$port"
        val clientId = "babycam-overlay-" + UUID.randomUUID().toString().take(8)

        val mqttClient = runCatching { MqttAsyncClient(brokerUrl, clientId, MemoryPersistence()) }
            .getOrElse {
                onConnectFailed?.invoke(it.message ?: "Could not create MQTT client")
                return
            }
        client = mqttClient

        mqttClient.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                runCatching { mqttClient.subscribe(topic, 0) }
            }

            override fun connectionLost(cause: Throwable?) {
                // Paho's automatic reconnect (set below) handles retrying on its own.
            }

            override fun messageArrived(receivedTopic: String?, message: MqttMessage?) {
                onTriggered()
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {
                // We never publish, so nothing to do here.
            }
        })

        val brokerUsername = username
        val brokerPassword = password
        val options = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = true
            connectionTimeout = 10
            keepAliveInterval = 30
            if (brokerUsername.isNotBlank()) userName = brokerUsername
            if (brokerPassword.isNotBlank()) password = brokerPassword.toCharArray()
        }

        runCatching {
            mqttClient.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    onConnected?.invoke()
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    onConnectFailed?.invoke(exception?.message ?: "Connection failed")
                }
            })
        }.onFailure { onConnectFailed?.invoke(it.message ?: "Connection failed") }
    }

    fun stop() {
        runCatching {
            client?.disconnect(0)
            client?.close()
        }
        client = null
    }
}
