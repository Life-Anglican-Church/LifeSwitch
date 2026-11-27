@echo off
setlocal
title LifeSWITCH ATEM connection check
set "LSW_ROOT=%ProgramW6432%\LifeSWITCH"
if not exist "%LSW_ROOT%\runtime\node.exe" (
    echo The LifeSWITCH booth service was not found in Program Files.
    pause
    exit /b 1
)
"%LSW_ROOT%\runtime\node.exe" -e "const f=require('node:fs').readFileSync(process.argv[1],'utf8'); eval(f.split(/\r?\n:: BEGIN JAVASCRIPT\r?\n/)[1])" "%~f0" "%LSW_ROOT%"
echo.
pause
exit /b
:: BEGIN JAVASCRIPT
const path = require('node:path')
const { isIPv4 } = require('node:net')
const readline = require('node:readline/promises')
const { Atem, Enums } = require(path.join(process.argv[2], 'booth', 'node_modules', 'atem-connection'))
async function probe(address, singleThread) {
  const atem = new Atem({ debugBuffers: true, disableMultithreaded: singleThread })
  const counts = { sent: 0, received: 0, commands: 0, initialized: 0 }
  const messages = new Set()
  let report
  atem.on('info', message => {
    if (message.startsWith('SEND ')) counts.sent++
    else if (message.startsWith('RECV ')) counts.received++
    else if (messages.size < 12) messages.add(message)
  })
  atem.on('error', message => { if (messages.size < 12) messages.add(String(message)) })
  atem.on('receivedCommands', commands => { counts.commands += commands.length })
  atem.on('connected', () => {
    counts.initialized++
    const state = atem.state
    const inputs = Object.values(state?.inputs || {})
    report = {
      product: state?.info?.productIdentifier,
      protocolVersion: state?.info?.apiVersion,
      mixEffect: Boolean(state?.video?.mixEffects?.[0]),
      downstreamKey1: Boolean(state?.video?.downstreamKeyers?.[0]),
      inputCount: inputs.length,
      externalInputs: inputs.filter(input => input.portType === Enums.InternalPortType.External).length,
    }
  })
  console.log(singleThread ? '\nSingle-thread check' : '\nNormal LifeSWITCH transport check')
  try {
    await atem.connect(address)
    await new Promise(resolve => setTimeout(resolve, 6500))
    console.log(JSON.stringify(counts))
    if (report) console.log(JSON.stringify(report))
    for (const message of messages) console.log(message)
    return counts.initialized > 0
  } finally {
    await atem.destroy()
  }
}
async function main() {
  console.log('Read-only test. Keep ATEM Software Control open. No switcher controls will be sent.')
  const input = readline.createInterface({ input: process.stdin, output: process.stdout })
  let address
  try {
    address = (await input.question('ATEM IPv4 address from ATEM Setup: ')).trim()
  } finally { input.close() }
  if (!isIPv4(address)) throw new Error('Enter a valid ATEM IPv4 address.')
  if (!(await probe(address, false))) await probe(address, true)
  console.log('\nDone. Share these results with Codex. No pairing code is included.')
}
main().catch(error => { console.error(error.message); process.exitCode = 1 })
