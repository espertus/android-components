/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.lib.nearby

import android.Manifest
import android.content.Context
import android.os.Build
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.PayloadTransferUpdate.Status
import com.google.android.gms.nearby.connection.Strategy
import mozilla.components.support.base.log.logger.Logger
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentHashMap

/**
 * A class that can be run on two devices to allow them to connect. This supports sending a single
 * message at a time in each direction. It contains internal synchronization and may be accessed
 * from any thread
 *
 * @constructor Constructs a new connection, which will call [NearbyConnectionListener.updateState]
 *     with an argument of type [ConnectionState.Isolated]. No further action will be taken unless
 *     other methods are called by the client.
 * @param context context needed to initiate connection, used only at start
 * @param name name shown by this device to other devices
 * @param authenticate whether to authenticate the connection (true) or make it automatically (false)
 */
class NearbyConnection(
    private val context: Context,
    private val name: String = Build.MODEL,
    private val authenticate: Boolean = true
) {
    /**
     * Listener to be notified of changes of status and message transmission. When this
     * is set, its [NearbyConnectionListener.updateState] method is immediately called.
     */
    var listener: NearbyConnectionListener? = null
        set(value) {
            field = value
            value?.updateState(if (::connectionState.isInitialized) connectionState else ConnectionState.Isolated)
        }

    // I assume that the number of endpoints encountered during the lifetime of the application
    // will be small and do not remove them from the map.
    private val endpointIdsToNames = ConcurrentHashMap<String, String>()

    /**
     * The state of the connection. Changes in state are communicated through
     * [NearbyConnectionListener.updateState].
     */
    public sealed class ConnectionState {
        val name = javaClass.simpleName

        /**
         * There is no connection to another device and no attempt to connect.
         */
        object Isolated : ConnectionState()

        /**
         * This device is advertising its presence.
         */
        object Advertising : ConnectionState()

        /**
         * This device is trying to discover devices that are advertising.
         */
        object Discovering : ConnectionState()

        /**
         * This device is in the process of authenticating with a neighboring device.
         */
        class Authenticating(
            // Sealed classes can't be inner, so we need to pass in the connection.
            private val nearbyConnection: NearbyConnection,
            /**
             * The ID of the neighbor, which is not meant for human readability.
             */
            val neighborId: String,

            /**
             * The human readable name of the neighbor.
             */
            val neighborName: String,

            /**
             * A short unique token of printable characters shared by both sides of the
             * pending connection.
             */
            val token: String
        ) : ConnectionState() {
            /**
             * Accepts the connection to the neighbor.
             */
            fun accept() {
                nearbyConnection.connectionsClient.acceptConnection(neighborId, nearbyConnection.payloadCallback)
                nearbyConnection.updateState(Connecting(neighborId, neighborName))
            }

            /**
             * Rejects the connection to the neighbor.
             */
            fun reject() {
                nearbyConnection.connectionsClient.rejectConnection(neighborId)
                // This should put us back in advertising or discovering.
                nearbyConnection.updateState(nearbyConnection.connectionState)
            }
        }

        /**
         * The connection has been successfully authenticated (or authentication is disabled).
         * Unless an error occurs, the next state will be [ReadyToSend].
         */
        class Connecting(val neighborId: String, val neighborName: String) : ConnectionState()

        /**
         * A connection has been made to a neighbor and this device may send a message.
         * This state is followed by [Sending] or [Failure].
         */
        class ReadyToSend(val neighborId: String, val neighborName: String?) : ConnectionState()

        /**
         * A message is being sent from this device. This state is followed by [ReadyToSend] or
         * [Failure].
         */
        class Sending(val neighborId: String, val neighborName: String?, val payloadId: Long) : ConnectionState()

        /**
         * A failure has occurred.
         *
         * @param message an error message describing the failure
         */
        class Failure(val message: String) : ConnectionState()
    }

    private var connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(context)

    // The is mutated only in updateState(), which can be called from both the main thread and in
    // callbacks so is synchronized.
    private lateinit var connectionState: ConnectionState

    // This method is called from both the main thread and callbacks.
    @Synchronized
    private fun updateState(cs: ConnectionState) {
        connectionState = cs
        listener?.updateState(cs)
    }

    /**
     * Starts advertising this device. After calling this, the state will be updated to
     * [ConnectionState.Advertising] or [ConnectionState.Failure]. If all goes well, eventually
     * the state will be updated to [ConnectionState.Authenticating] (if [authenticate] is true)
     * or [ConnectionState.Connecting]. A client should call either [startAdvertising] or
     * [startDiscovering] to make a connection, not both.
     */
    fun startAdvertising() {
        connectionsClient.startAdvertising(
            name,
            PACKAGE_NAME,
            connectionLifecycleCallback,
            AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        ).addOnSuccessListener {
            updateState(ConnectionState.Advertising)
        }.addOnFailureListener {
            reportError("failed to start advertising: $it")
        }
    }

    /**
     * Starts trying to discover nearby advertising devices. After calling this, the state will
     * be updated to [ConnectionState.Discovering] or [ConnectionState.Failure]. If all goes well,
     * eventually the state will be updated to [ConnectionState.Authenticating] (if [authenticate]
     * is true) or [ConnectionState.Connecting]. A client should call either [startAdvertising] or
     * [startDiscovering] to make a connection, not both.
     */
    fun startDiscovering() {
        connectionsClient.startDiscovery(
            PACKAGE_NAME, endpointDiscoveryCallback,
            DiscoveryOptions.Builder().setStrategy(STRATEGY).build())
            .addOnSuccessListener {
                updateState(ConnectionState.Discovering)
            }.addOnFailureListener {
                reportError("failed to start discovering: $it")
            }
    }

    // Discovery
    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            connectionsClient.requestConnection(name, endpointId, connectionLifecycleCallback)
            updateState(ConnectionState.Connecting(endpointId, info.endpointName))
        }

        override fun onEndpointLost(endpointId: String) {
            updateState(ConnectionState.Discovering)
        }
    }

    // Used within startAdvertising() and startDiscovering() (via endpointDiscoveryCallback)
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            // This is the last time we have access to the endpoint name, so cache it.
            endpointIdsToNames.put(endpointId, connectionInfo.endpointName)
            if (authenticate) {
                updateState(
                    ConnectionState.Authenticating(
                        this@NearbyConnection,
                        endpointId,
                        connectionInfo.endpointName,
                        connectionInfo.authenticationToken
                    )
                )
            } else {
                connectionsClient.acceptConnection(endpointId, payloadCallback)
                updateState(ConnectionState.Connecting(endpointId, connectionInfo.endpointName))
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectionsClient.stopDiscovery()
                connectionsClient.stopAdvertising()
                updateState(ConnectionState.ReadyToSend(
                    endpointId,
                    endpointIdsToNames[endpointId]))
            } else {
                reportError("onConnectionResult: connection failed with status ${result.status}")
            }
        }

        override fun onDisconnected(endpointId: String) {
            updateState(ConnectionState.Isolated)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            listener?.receiveMessage(
                endpointId,
                endpointIdsToNames[endpointId],
                String(
                    when (payload.getType()) {
                        Payload.Type.BYTES -> payload.asBytes()!!
                        Payload.Type.STREAM -> payload.asStream()!!.asInputStream().toString().toByteArray(PAYLOAD_ENCODING)
                        Payload.Type.FILE -> ByteArray(0)
                        // The Payload API guarantees it will be one of the above 3 options.
                        else -> ByteArray(0)},
                    PAYLOAD_ENCODING))
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Make sure it's reporting on our outgoing message, not an incoming one.
            if (update.status == Status.SUCCESS &&
                (connectionState as? ConnectionState.Sending)?.payloadId == update.payloadId) {
                listener?.messageDelivered(update.payloadId)
                updateState(ConnectionState.ReadyToSend(
                    endpointId,
                    endpointIdsToNames[endpointId]))
            }
        }
    }

    /**
     * Sends a message to a connected device. If the current state is not
     * [ConnectionState.ReadyToSend], the message will not be sent.
     *
     * @param message the message to send
     * @return an id that will be later passed back through
     *   [NearbyConnectionListener.messageDelivered], or null if the message could not be sent
     */
    fun sendMessage(message: String): Long? {
        val state = connectionState
        if (state is ConnectionState.ReadyToSend) {
            val payload =
                if (message.length <= MAX_PAYLOAD_BYTES) {
                    Payload.fromBytes(message.toByteArray(PAYLOAD_ENCODING))
                } else {
                    // Logically, it might make more sense to use Payload.fromFile() since we
                    // know the size of the string, than Payload.fromStream(), but we would
                    // have to create a file locally to use use the former.
                    Payload.fromStream(ByteArrayInputStream(message.toByteArray(PAYLOAD_ENCODING)))
                }
            connectionsClient.sendPayload(state.neighborId, payload)
            updateState(ConnectionState.Sending(
                state.neighborId,
                endpointIdsToNames[state.neighborId],
                payload.id))
            return payload.id
        }
        return null
    }

    /**
     * Breaks any connections to neighboring devices. This also stops advertising and
     * discovering. The state will be updated to [ConnectionState.Isolated].
     */
    fun disconnect() {
        connectionsClient.stopAllEndpoints() // also stops advertising and discovery
        updateState(ConnectionState.Isolated)
    }

    private fun reportError(msg: String) {
        Logger.error(msg)
        updateState(ConnectionState.Failure(msg))
    }

    companion object {
        private const val PACKAGE_NAME = "mozilla.components.lib.nearby"
        private val PAYLOAD_ENCODING: Charset = Charsets.UTF_8
        private val STRATEGY = Strategy.P2P_STAR
        // The maximum number of bytes to send through Payload.fromBytes();
        // otherwise, use Payload.getStream()
        private val MAX_PAYLOAD_BYTES = ConnectionsClient.MAX_BYTES_DATA_SIZE

        /**
         * The permissions needed by [NearbyConnection]. It is the client's responsibility
         * to ensure that all are granted before constructing an instance of this class.
         */
        val PERMISSIONS: Array<String> = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE
        )
    }
}

/**
 * Interface definition for listening to changes in a [NearbyConnection].
 */
interface NearbyConnectionListener {
    /**
     * Called whenever the connection's state is set. In the absence of failures, the
     * new state should differ from the prior state, but that is not guaranteed.
     *
     * @param connectionState the current state
     */
    fun updateState(connectionState: NearbyConnection.ConnectionState)

    /**
     * Called when a message is received from a neighboring device.
     *
     * @param neighborId the ID of the neighboring device
     * @param neighborName the name of the neighboring device
     * @param message the message
     */
    fun receiveMessage(neighborId: String, neighborName: String?, message: String)

    /**
     * Called when a message has been successfully delivered to a neighboring device.
     */
    fun messageDelivered(payloadId: Long)
}
