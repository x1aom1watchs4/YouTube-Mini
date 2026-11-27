import interconnect from '@system.interconnect'

const connection = interconnect.instance()
const listeners = []
let state = 'disconnected'
let lastError = ''

function publish(extra) {
  const event = Object.assign({ state, error: lastError }, extra || {})
  listeners.slice().forEach((listener) => {
    try { listener(event) } catch (error) {
      console.error('YouTube Mini listener error', error)
    }
  })
}

function setState(nextState, error) {
  state = nextState
  lastError = error || ''
  publish()
}

function readMessage(callbackData) {
  if (!callbackData) return null
  // Vela interconnect supplies the received payload as callbackData.data.
  let message = callbackData.data !== undefined ? callbackData.data : callbackData
  if (typeof message === 'string') {
    try { message = JSON.parse(message) } catch (error) { return null }
  }
  if (!message || typeof message !== 'object' || message.v !== 1 || typeof message.type !== 'string') {
    return null
  }
  return message
}

connection.onopen = function () {
  setState('connected', '')
}

connection.onclose = function (event) {
  const reason = event && event.data ? String(event.data) : ''
  setState('disconnected', reason)
}

connection.onerror = function (event) {
  const reason = event && event.data ? String(event.data) : 'Connection error'
  setState('disconnected', reason)
}

connection.onmessage = function (event) {
  const message = readMessage(event)
  if (message) publish({ message })
}

export default {
  getState() {
    return state
  },

  subscribe(listener) {
    if (typeof listener !== 'function') return function () {}
    listeners.push(listener)
    return function () {
      const index = listeners.indexOf(listener)
      if (index >= 0) listeners.splice(index, 1)
    }
  },

  checkConnection() {
    setState('connecting', '')
    connection.diagnosis({
      timeout: 10000,
      success: (result) => {
        if (result && result.status === 0) {
          setState('connected', '')
        } else {
          setState('disconnected', 'Connection unavailable (' + (result ? result.status : 'unknown') + ')')
        }
      },
      fail: (error, code) => {
        setState('disconnected', String(error || code || 'Connection unavailable'))
      }
    })
  },

  send(type, payload) {
    if (state !== 'connected') return false
    connection.send({
      data: { v: 1, type, payload: payload || {} },
      success: function () {},
      fail: (error, code) => {
        setState('disconnected', String(error || code || 'Send failed'))
      }
    })
    return true
  }
}
