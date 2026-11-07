import { strict as assert } from 'node:assert';
import { test, describe, beforeEach } from 'node:test';
import { config, isAllowed } from '../../src/config';

describe('isAllowed — lista branca de números', () => {
  beforeEach(() => {
    config.allowedNumbers = ['5511999999999'];
  });

  test('aceita número exato', () => {
    assert.equal(isAllowed('5511999999999'), true);
    assert.equal(isAllowed('+55 11 99999-9999'), true);
  });

  test('recusa número fora da lista', () => {
    assert.equal(isAllowed('5511888888888'), false);
  });

  test('compara pelo sufixo (código de país diferente)', () => {
    config.allowedNumbers = ['9988887777'];
    assert.equal(isAllowed('55119988887777'), true);
  });

  test('lista vazia aceita todos', () => {
    config.allowedNumbers = [];
    assert.equal(isAllowed('5511999999999'), true);
  });

  test('número vazio é recusado', () => {
    assert.equal(isAllowed(''), false);
    assert.equal(isAllowed('abc'), false);
  });

  test('vários números na lista', () => {
    config.allowedNumbers = ['5511999999999', '5511888888888'];
    assert.equal(isAllowed('5511888888888'), true);
    assert.equal(isAllowed('5511777777777'), false);
  });
});
