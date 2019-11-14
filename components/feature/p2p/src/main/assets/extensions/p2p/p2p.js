/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */
//require('freeze-dry')

console.log("Hello world!!!");
let fd = require('freeze-dry');
console.log(fd.default);
console.log(fd.default());