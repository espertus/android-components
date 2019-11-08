/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.p2p.internal

import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.feature.p2p.P2PFeature
import mozilla.components.feature.p2p.view.P2PBar
import mozilla.components.feature.p2p.view.P2PView
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.tabs.TabsUseCases
import mozilla.components.lib.nearby.NearbyConnection
import mozilla.components.lib.nearby.NearbyConnection.ConnectionState
import mozilla.components.lib.nearby.NearbyConnectionObserver
import mozilla.components.support.base.log.logger.Logger

/**
 * Controller that mediates between [P2PView] and [NearbyConnection].
 */
internal class P2PController(
    private val store: BrowserStore,
    private val thunk: () -> NearbyConnection,
    private val view: P2PView,
    private val tabsUseCases: TabsUseCases,
    private val sessionUseCases: SessionUseCases,
    private val sender: P2PFeature.P2PFeatureSender
) : P2PView.Listener {
    private val logger = Logger("P2PController")

    private val observer = object : NearbyConnectionObserver {
        @Synchronized
        override fun onStateUpdated(connectionState: ConnectionState) {
            savedConnectionState = connectionState
            view.updateStatus(connectionState.name)
            logger.error("Entering state ${connectionState.name}")
            when (connectionState) {
                is ConnectionState.Authenticating -> {
                    view.authenticate(
                        connectionState.neighborId,
                        connectionState.neighborName,
                        connectionState.token
                    )
                }
                is ConnectionState.ReadyToSend -> view.readyToSend()
                is ConnectionState.Failure -> view.failure(connectionState.message)
                is ConnectionState.Isolated -> view.clear()
            }
        }

        override fun onMessageDelivered(payloadId: Long) {
            // For now, do nothing.
        }

        override fun onMessageReceived(neighborId: String, neighborName: String?, message: String) {
            if (message.length > 1) {
                when (message[0]) {
                    MESSAGE_PREFIX_FOR_HTML -> view.receivePage(
                        neighborId, neighborName, message.substring(1)
                    )
                    MESSAGE_PREFIX_FOR_URL -> view.receiveUrl(
                        neighborId, neighborName, message.substring(1)
                    )
                    else -> reportError("Cannot parse incoming message $message")
                }
            } else {
                reportError("Trivial message received: '$message'")
            }
        }
    }

    fun start() {
        view.listener = this
        if (nearbyConnection == null) {
            nearbyConnection = thunk()
        }
        nearbyConnection?.register(observer, view as P2PBar)
    }

    fun stop() {
        nearbyConnection?.unregisterObservers()
    }

    @Synchronized
    private fun reportError(msg: String) {
        Logger.error(msg)
        view.failure(msg)
    }

    inline fun <reified T : ConnectionState> cast() =
        (savedConnectionState as? T).also {
            if (it == null) {
                reportError("savedConnection was expected to be type ${T::class} but is $savedConnectionState")
            }
        }

    // P2PView.Listener implementation

    override fun onAdvertise() {
        nearbyConnection?.startAdvertising()
    }

    override fun onDiscover() {
        nearbyConnection?.startDiscovering()
    }

    override fun onAccept(token: String) {
        cast<ConnectionState.Authenticating>()?.accept()
    }

    override fun onReject(token: String) {
        cast<ConnectionState.Authenticating>()?.reject()
    }

    override fun onSetUrl(url: String, newTab: Boolean) {
        if (newTab) {
            tabsUseCases.addTab(url)
        } else {
            sessionUseCases.loadUrl(url)
        }
    }

    override fun onReset() {
        nearbyConnection?.disconnect()
    }

    override fun onSendUrl() {
        if (cast<ConnectionState.ReadyToSend>() != null) {
            store.state.selectedTab?.content?.url?.let {
                if (nearbyConnection?.sendMessage("$MESSAGE_PREFIX_FOR_URL$it") == null) {
                    reportError("Unable to send message: sendMessage() returns null")
                }
            } ?: run {
                reportError("Unable to get URL to send")
            }
        }
    }

    override fun onSendPage() {
        if (cast<ConnectionState.ReadyToSend>() != null) {
            sender.requestHtml()
        }
    }

    fun onPageReadyToSend(page: String) {
        if (cast<ConnectionState.ReadyToSend>() != null) {
            if (nearbyConnection?.sendMessage("$MESSAGE_PREFIX_FOR_HTML$page") == null) {
                reportError("Unable to send message: sendMessage() returns null")
            }
            return
        }
    }

    override fun onLoadData(data: String, mimeType: String) {
        // There's a bug in loadData() that makes it necessary to use base64 encoding.
        sessionUseCases.loadData(data, mimeType, "base64")
    }

    companion object {
        const val MESSAGE_PREFIX_FOR_URL = 'U'
        const val MESSAGE_PREFIX_FOR_HTML = 'H'

        private var savedConnectionState: ConnectionState? = null
        private var nearbyConnection: NearbyConnection? = null
    }
}
