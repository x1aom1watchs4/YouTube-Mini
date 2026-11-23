import interconnect from '@system.interconnect'

const connection = interconnect.instance()
const listeners = []
let state = 'disconnected'
let lastError = ''

function publish() {
  listeners.forEach((listener) => {
    try { listener({ state, error: lastError }) } catch (e) {}
  })
}

function setState(nextState, error) {
  state = nextState
  lastError = error || ''
  publish()
}

connection.onopen = function () {
  setState('connected', '')
}

connection.onclose = function (event) {
  setState('disconnected', event && event.data ? String(event.data) : '')
}

connection.onerror = function (event) {
  setState('disconnected', event && event.data ? String(event.data) : 'Connection error')
}

connection.onmessage = function (event) {
  let message = event && event.data
  if (typeof message === 'string') {
    try { message = JSON.parse(message) } catch (e) { return }
  }
  listeners.forEach((listener) => {
    try { listener({ state, message }) } catch (e) {}
  })
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
        if (result && result.status === 0) setState('connected', '')
        else setState('disconnected', 'Connection unavailable (' + (result ? result.status : 'unknown') + ')')
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
