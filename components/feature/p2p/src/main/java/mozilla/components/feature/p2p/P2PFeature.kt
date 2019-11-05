/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.p2p

import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import mozilla.components.browser.session.SelectionAwareSessionObserver
import mozilla.components.browser.session.Session
import mozilla.components.browser.session.SessionManager
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.Port
import mozilla.components.feature.p2p.internal.P2PController
import mozilla.components.feature.p2p.view.P2PView
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.tabs.TabsUseCases
import mozilla.components.lib.nearby.NearbyConnection
import mozilla.components.support.base.feature.BackHandler
import mozilla.components.support.base.feature.LifecycleAwareFeature
import mozilla.components.support.base.feature.OnNeedToRequestPermissions
import mozilla.components.support.base.feature.PermissionsFeature
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.support.webextensions.WebExtensionController

/**
 * Feature implementation for peer-to-peer communication between browsers.
 */
class P2PFeature(
    view: P2PView,
    store: BrowserStore,
    private val engine: Engine,
    thunk: () -> NearbyConnection,
    tabsUseCases: TabsUseCases,
    sessionUseCases: SessionUseCases,
    private val sessionManager: SessionManager,
    override val onNeedToRequestPermissions: OnNeedToRequestPermissions,
    private val onClose: (() -> Unit)
) : SelectionAwareSessionObserver(sessionManager), LifecycleAwareFeature, PermissionsFeature,
    BackHandler {
    @VisibleForTesting
    internal var controller = P2PController(store, thunk, view, tabsUseCases, sessionUseCases)

    private val logger = Logger("P2P")
    private var session: SessionState? = null

    @VisibleForTesting
    // This is an internal var to make it mutable for unit testing purposes only
    internal var extensionController = WebExtensionController(P2P_EXTENSION_ID, P2P_EXTENSION_URL)

    // LifeCycleAwareFeature implementation

    override fun start() {
        requestNeededPermissions()
    }

    override fun stop() {
        super.stop()
        controller.stop()
    }

    // PermissionsFeature implementation

    private var ungrantedPermissions = NearbyConnection.PERMISSIONS.filter {
        ContextCompat.checkSelfPermission(
            view.asView().context,
            it
        ) != PackageManager.PERMISSION_GRANTED
    }

    private fun requestNeededPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ungrantedPermissions.isEmpty()) {
                onPermissionsGranted()
            } else {
                onNeedToRequestPermissions(ungrantedPermissions.toTypedArray())
            }
        } else {
            Logger.error("Cannot continue on pre-Marshmallow device")
        }
    }

    override fun onPermissionsResult(permissions: Array<String>, grantResults: IntArray) {
        // Sometimes ungrantedPermissions still shows a recently accepted permission as being not granted,
        // so we need to check grantResults instead.
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            onPermissionsGranted()
        } else {
            Logger.error("Cannot continue due to missing permissions $ungrantedPermissions")
        }
    }

    private fun onPermissionsGranted() {
        startExtension()
    }

    private fun startExtension() {
        observeSelected()
        registerP2PContentMessageHandler()

        extensionController.install(engine)
        controller.start { j ->
            activeSession?.let {
                logger.error("I'm sending this message: ${j["message"]}")
                extensionController.sendContentMessage(
                    j,
                    sessionManager.getOrCreateEngineSession(it)
                )
            }
        }
    }

    @VisibleForTesting
    internal fun registerP2PContentMessageHandler(session: Session? = activeSession) {
        if (session == null) {
            return
        }

        val engineSession = sessionManager.getOrCreateEngineSession(session)
        val messageHandler = P2PContentMessageHandler(session)
        extensionController.registerContentMessageHandler(engineSession, messageHandler)
        extensionController.registerBackgroundMessageHandler(P2PBackgroundtMessageHandler(session))
    }

    private inner class P2PContentMessageHandler(
        private val session: Session
    ) : MessageHandler {
        override fun onPortConnected(port: Port) {
            logger.error("P2PC port is connected!")
        }

        override fun onPortMessage(message: Any, port: Port) {
            logger.error("P2PC receives a port message: $message")
        }

        override fun onMessage(message: Any, source: EngineSession?): Any? {
            logger.error("P2PC receives a message: $message")
            return super.onMessage(message, source)
        }

        override fun onPortDisconnected(port: Port) {
            logger.error("P2PC receives a port disconnect")
            super.onPortDisconnected(port)
        }
    }

    private inner class P2PBackgroundtMessageHandler(
        private val session: Session
    ) : MessageHandler {
        override fun onPortConnected(port: Port) {
            logger.error("P2PB port is connected!")
        }

        override fun onPortMessage(message: Any, port: Port) {
            logger.error("P2PB receives a port message: $message")
        }

        override fun onMessage(message: Any, source: EngineSession?): Any? {
            logger.error("P2PB receives a message: $message")
            return super.onMessage(message, source)
        }

        override fun onPortDisconnected(port: Port) {
            logger.error("P2PB receives a port disconnect")
            super.onPortDisconnected(port)
        }
    }

    // BackHandler implementation
    override fun onBackPressed(): Boolean {
        // Nothing, for now
        return true
    }

    @VisibleForTesting
    companion object {
        internal const val P2P_EXTENSION_ID = "mozacP2P"
        internal const val P2P_EXTENSION_URL = "resource://android/assets/extensions/p2p/"
    }
}
