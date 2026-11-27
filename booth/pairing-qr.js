'use strict'

const fs = require('node:fs')
const net = require('node:net')
const qrcode = require('qrcode-generator')

const config = JSON.parse(fs.readFileSync(process.argv[2], 'utf8').replace(/^\uFEFF/, ''))
if (!net.isIPv4(config.bind) || !/^[a-f0-9]{32}$/.test(config.token)) {
  throw new Error('LifeSWITCH pairing configuration is invalid.')
}
const qr = qrcode(0, 'M')
qr.addData('lifeswitch://pair?host=' + config.bind + '&code=' + config.token)
qr.make()
const size = qr.getModuleCount()
const rows = Array.from({ length: size }, (_, row) =>
  Array.from({ length: size }, (_, column) => qr.isDark(row, column) ? '1' : '0').join(''))
console.log(JSON.stringify(rows))
