/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */
let port = browser.runtime.connectNative("mozacP2P");

port.onMessage.addListener((message) => {
  switch (message.action) {
    case 'get_html':
      port.postMessage("dummy page");
      break;
    default:
      console.log("I do not know how to handle this action: ${message.action}")
  }
});

port.onDisconnect.addListener((p) => {
  if (p.error) {
    console.log("Wah! Disconnected due to an error: ${p.error.message}");
  }
});

window.addEventListener("unload", (event) => { port.disconnect() }, false);
