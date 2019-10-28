/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.p2p.internal

import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.store.BrowserStore
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
    private val sessionUseCases: SessionUseCases
) : P2PView.Listener {
    private lateinit var nearbyConnection: NearbyConnection
    private var savedConnectionState: ConnectionState? = null

    fun start() {
        view.listener = this
        nearbyConnection = thunk()
        nearbyConnection.register(
            object : NearbyConnectionObserver {
                @Synchronized
                override fun onStateUpdated(connectionState: ConnectionState) {
                    savedConnectionState = connectionState
                    view.updateStatus(connectionState.name)
                    when (connectionState) {
                        is ConnectionState.Authenticating -> view.authenticate(
                            connectionState.neighborId,
                            connectionState.neighborName,
                            connectionState.token)
                        is ConnectionState.ReadyToSend -> view.readyToSend()
                        is ConnectionState.Failure -> view.failure(connectionState.message)
                        is ConnectionState.Isolated -> view.clear()
                    }
                }

                override fun onMessageDelivered(payloadId: Long) {
                    // For now, do nothing.
                }

                override fun onMessageReceived(neighborId: String, neighborName: String?, message: String) {
                    view.receiveUrl(neighborId, neighborName, message)
                }
            },
            // I need to do this cast to get an object that extends View
            view as P2PBar
        )
    }

    fun stop() {}

    // P2PView.Listener implementation

    override fun onAdvertise() {
        nearbyConnection.startAdvertising()
    }

    override fun onDiscover() {
        nearbyConnection.startDiscovering()
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
        nearbyConnection.disconnect()
    }

    override fun onSendUrl() {
        if (cast<ConnectionState.ReadyToSend>() != null) {
            val payloadID = nearbyConnection.sendMessage(store.state.selectedTab?.content?.url
                ?: "no URL")
            if (payloadID == null) {
                reportError("Unable to send message: sendMessage() returns null")
            }
        }
    }

    override fun onLoadData(data: String, mimeType: String) {
        // There's a bug in loadData() that makes it necessary to use base64 encoding.
        sessionUseCases.loadData(data, mimeType, "base64")
    }
}
