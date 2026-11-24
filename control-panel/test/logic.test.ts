import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { canStart, canStop, codeMatches, toPhase } from '../api/_logic.ts'

describe('toPhase', () => {
  it('tells "machine on" apart from "app ready"', () => {
    assert.equal(toPhase('running', false), 'booting')
    assert.equal(toPhase('running', true), 'ready')
  })
  it('maps every other EC2 state', () => {
    assert.equal(toPhase('stopped', false), 'stopped')
    assert.equal(toPhase('pending', false), 'starting')
    assert.equal(toPhase('stopping', false), 'stopping')
    assert.equal(toPhase('shutting-down', false), 'stopping')
    assert.equal(toPhase('terminated', false), 'unavailable')
    assert.equal(toPhase(undefined, false), 'unavailable')
  })
  it('never reports ready for a machine that is not running, even if the site answered', () => {
    assert.equal(toPhase('stopped', true), 'stopped')
  })
})

describe('codeMatches', () => {
  it('accepts only the exact code', () => {
    assert.equal(codeMatches('correct horse', 'correct horse'), true)
    assert.equal(codeMatches('correct horsE', 'correct horse'), false)
    assert.equal(codeMatches('correct', 'correct horse'), false)
  })
  it('refuses when no code is configured or none is given, so an empty setting is not an open door', () => {
    assert.equal(codeMatches('', ''), false)
    assert.equal(codeMatches('anything', undefined), false)
    assert.equal(codeMatches(undefined, 'secret'), false)
  })
})

describe('what may be done in each state', () => {
  it('starts only a stopped server', () => {
    assert.equal(canStart('stopped'), true)
    for (const s of ['running', 'pending', 'stopping', 'terminated', undefined]) {
      assert.equal(canStart(s), false)
    }
  })
  it('stops only a server that is on or on its way up', () => {
    assert.equal(canStop('running'), true)
    assert.equal(canStop('pending'), true)
    for (const s of ['stopped', 'stopping', 'terminated', undefined]) {
      assert.equal(canStop(s), false)
    }
  })
})
