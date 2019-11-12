/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.p2p.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.ConstraintLayout
import kotlinx.android.synthetic.main.mozac_feature_p2p_view.view.*
import mozilla.components.feature.p2p.R
import mozilla.components.support.base.log.logger.Logger

private const val DEFAULT_VALUE = 0

/**
 * A toolbar for peer-to-peer communication between browsers. Setting [listener] causes the
 * buttons to become active.
 */
@Suppress("TooManyFunctions")
class P2PBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr), P2PView {
    override var listener: P2PView.Listener? = null
    private val logger = Logger("P2PBar")

    init {
        inflate(getContext(), R.layout.mozac_feature_p2p_view, this)

        p2pAdvertiseBtn.setOnClickListener {
            require(listener != null)
            listener?.onAdvertise()
            showConnectButtons(false)
        }
        p2pDiscoverBtn.setOnClickListener {
            require(listener != null)
            listener?.onDiscover()
            showConnectButtons(false)
        }
        p2pSendUrlBtn.setOnClickListener {
            require(listener != null)
            listener?.onSendUrl()
            // p2pSendUrlBtn.isEnabled = false
        }
        p2pSendPageBtn.setOnClickListener {
            require(listener != null)
            listener?.onSendPage()
            // p2pSendPageBtn.isEnabled = false
        }
        p2pResetBtn.setOnClickListener {
            require(listener != null)
            listener?.onReset()
            clear()
        }
    }

    override fun initializeButtons(connectB: Boolean, sendB: Boolean) {
        showConnectButtons(connectB) // advertise, discover, !reset
        showSendButtons(sendB) // send URL, send page
    }

    private fun showButton(btn: Button, b: Boolean) {
        btn.visibility = if (b) View.VISIBLE else View.GONE
        btn.isEnabled = b
    }

    internal fun showConnectButtons(b: Boolean) {
        // Either the advertise and discover buttons are visible and enabled, or the reset button is.
        showButton(p2pAdvertiseBtn, b)
        showButton(p2pDiscoverBtn, b)
        showButton(p2pResetBtn, !b)
    }

    internal fun showSendButtons(b: Boolean = true) {
        showButton(p2pSendUrlBtn, b)
        showButton(p2pSendPageBtn, b)
    }

    override fun updateStatus(status: String) {
        p2pStatusText.text = status
    }

    override fun authenticate(neighborId: String, neighborName: String, token: String) {
        require(listener != null)
        AlertDialog.Builder(context)
            .setTitle("Accept connection to $neighborName")
            .setMessage("Confirm the code matches on both devices: $token")
            .setPositiveButton(android.R.string.yes) { _, _ -> listener?.onAccept(token) }
            .setNegativeButton(android.R.string.no) { _, _ -> listener?.onReject(token) }
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }

    override fun readyToSend() {
        require(listener != null)
        showSendButtons(true)
        showConnectButtons(false)
    }

    override fun receiveUrl(neighborId: String, neighborName: String?, url: String) {
        AlertDialog.Builder(context)
            .setTitle(
                context.getString(
                    R.string.mozac_feature_p2p_open_url_title, neighborName
                        ?: neighborId
                )
            )
            .setMessage(url)
            .setPositiveButton(context.getString(R.string.mozac_feature_p2p_open_in_current_tab)) { _, _ ->
                listener?.onSetUrl(
                    url,
                    newTab = false
                )
            }
            .setNeutralButton(context.getString(R.string.mozac_feature_p2p_open_in_new_tab)) { _, _ ->
                listener?.onSetUrl(
                    url,
                    newTab = true
                )
            }
            .setNegativeButton(android.R.string.no) { _, _ -> }
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }

    override fun receivePage(neighborId: String, neighborName: String?, page: String) {
        AlertDialog.Builder(context)
            .setTitle(
                context.getString(
                    R.string.mozac_feature_p2p_open_page_title, neighborName
                        ?: neighborId
                )
            )
            .setMessage("Tab choice is ignored.")
            .setPositiveButton(context.getString(R.string.mozac_feature_p2p_open_in_current_tab)) { _, _ ->
                listener?.onLoadData(
                    page,
                    "text/html"
                )
            }
            .setNeutralButton(context.getString(R.string.mozac_feature_p2p_open_in_new_tab)) { _, _ ->
                listener?.onLoadData(
                    page,
                    "text/html"
                )
            }
            .setNegativeButton(android.R.string.no) { _, _ -> }
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }

    override fun failure(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    override fun clear() {
        showConnectButtons(true)
        showSendButtons(false)
    }
}