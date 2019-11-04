/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

/*
Establish communication with native application.
*/
let port = browser.runtime.connectNative("p2p");

port.onMessage.addListener((event) => {
  console.log("Hooray! A message arrived for me!")
});

port.onDisconnect.addListener((p) => {
  if (p.error) {
    console.log(`Wah! Disconnected due to an error: ${p.error.message}`);
  }
});

window.addEventListener("unload", (event) => { console.log("Time to disconnect. Bye!"); port.disconnect() }, false);
