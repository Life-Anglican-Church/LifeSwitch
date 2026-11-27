'use strict'

const fs = require('node:fs')
const path = require('node:path')
const worker = path.join(path.dirname(require.resolve('atem-connection')), 'lib', 'atemSocketChild.js')
const source = fs.readFileSync(worker, 'utf8')
const replacement = `    async _retransmitFrom(fromId) {
        this.log(` + '`Command packet ${fromId} unacknowledged. Reconnecting without retransmission.`' + `);
        await this.restartConnection();
    }
`
const start = source.indexOf('    async _retransmitFrom(fromId) {')
const end = source.indexOf('    _triggerRetransmitTimer()', start)
if (source.includes(replacement)) process.exit(0)
if (start < 0 || end < 0 || !source.slice(start, end).includes('this._sendPacket(sentPacket.payload)')) {
  throw new Error('Unexpected ATEM transport implementation. Review retransmit handling before packaging.')
}
fs.writeFileSync(worker, source.slice(0, start) + replacement + source.slice(end))
