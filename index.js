'use strict'

import { NativeModules, Platform } from 'react-native'
import bigInt from 'big-integer';
import { Buffer } from 'buffer'
import hasher from 'hash.js'
import ECCError, { ErrorCode, AndroidErrorCode, IOSErrorCode } from './ECCError';

const { RNECC } = NativeModules

const algorithm = 'sha256'
const encoding = 'base64'
const curve = 'p256'
const bits = 256

let serviceID
let accessGroup

function setServiceID(id) {
  if (serviceID) throw new Error('serviceID can only be set once')
  serviceID = id
}

/**
 * Generates public and private keys.
 *
 * The public key is returned with the given callback.
 * The private key is saved in the Keychain/Keystore.
 *
 * @return {Promise} It will reject with an error if the generation fails, it
 * will resolve with the public key in base64 if it succeeds.
 */
function generateKeys(restricted = false) {
  checkServiceID()

  return promisify(RNECC.generateECPair, {
    service: serviceID,
    accessGroup,
    restricted,
    curve,
    bits,
  }).then(base64ToHex)
}

/**
 * Sign some data with a given public key.
 * The user will be prompted with biometric authentication to sign data.
 *
 * @param {string} publicKey Public key (hex) to use to sign given data. The key must
 * have been generated with the `generateKeys` method.
 * @param {string} data Data to sign.
 * @param {string} ops.promptTitle Title of the biometric prompt.
 * @param {string} ops.promptMessage Message of the biometric prompt.
 * @param {string} ops.promptCancel Label of cancel button of the biometric prompt.
 * @return {Promise} Will reject with an error if the signing fails, will
 * resolve with signed data if it succeeds.
 */
function sign(publicKey, data, ops = {}) {
  checkServiceID()
  assert(typeof publicKey === 'string')
  assert(typeof data === 'string')

  return promisify(RNECC.sign, {
    service: serviceID,
    accessGroup,
    pub: hexToBase64(publicKey),
    hash: getHash(data),
    promptTitle: ops.promptTitle || '',
    promptMessage: ops.promptMessage || '',
    promptCancel: ops.promptCancel || '',
  }).then(base64ToHex)
}

/**
 * verifies a signature
 * @param {string} publicKey - pubKey corresponding to private key to sign hash with
 * @param {string} data - signed data
 * @param {suffer} sig - signature
 */
function verify(publicKey, data, sig) {
  checkServiceID()
  assert(typeof publicKey === 'string')
  assert(typeof data === 'string')
  assert(typeof sig === 'string')

  return promisify(RNECC.verify, {
    pub: hexToBase64(publicKey),
    sig: hexToBase64(sig),
    hash: getHash(data)
  })
}

/**
 * checks if key was created in secure execution environment (SecureEnclave/TEE)
 * @param {string} publicKey - pubKey corresponding to private key to sign hash with
 */
 function isKeyHardwareBacked(publicKey) {
  checkServiceID()
  assert(typeof publicKey === 'string')

  return promisify(RNECC.isKeyHardwareBacked, {
    pub: hexToBase64(publicKey),
  })
}

/**
 * Dismiss signing modal if open.
 *
 * This method only works on Android. On iOS it is not possible to
 * programmatically dismiss the dialog (everything is handled by the Keychain).
 * We still implement the method for iOS to avoid compatibility issues.
 *
 * @return {Promise} It will always resolve when the operation completes.
 */
function cancelSigning() {
  return promisify(RNECC.cancelSigning, {})
    .catch(() => { /* Force promise to always resolve */ })
}

/**
 * Compute x and y coordinates for a given base64 public key.
 *
 * @param {string} publicKeyBase64 Public key in base 64.
 * @return {{ x: string, y: string }} Coordinates of the given public key,
 * represented as strings because they are too long for numbers.
 */
function computeCoordinates(publicKeyBase64) {
  assert(typeof publicKeyBase64 === 'string')

  const publicKeyHex = Buffer.from(publicKeyBase64, 'base64').toString('hex');
  const publicKeyHexNo4 = publicKeyHex.slice(2);

  const xHex = publicKeyHexNo4.slice(0, 64);
  const yHex = publicKeyHexNo4.slice(64, 128);

  const x = bigInt(xHex, 16).toString();
  const y = bigInt(yHex, 16).toString();

  return { x, y };
}

function promisify(fnWithCallback, params) {
  return new Promise((resolve, reject) => {
    fnWithCallback(params, (nativeErrorCode, response) => {
      if (nativeErrorCode) {
        const errorCode = Platform.select({
          android: AndroidErrorCode[nativeErrorCode],
          ios: IOSErrorCode[nativeErrorCode],
        }) || ErrorCode.Generic;
        reject(new ECCError(errorCode, nativeErrorCode))
      } else {
        resolve(response)
      }
    });
  });
}

function assert (statement, errMsg) {
  if (!statement) throw new Error(errMsg || 'assertion failed')
}

function checkServiceID () {
  if (!serviceID) {
    throw new Error('call setServiceID() first')
  }
}

function getHash (data) {
  const arr = hasher[algorithm]().update(data).digest()
  return new Buffer(arr).toString(encoding)
}

function base64ToHex(base64Str) {
  return Buffer.from(base64Str, 'base64').toString('hex')
}

function hexToBase64(hexStr) {
  return Buffer.from(hexStr, 'hex').toString('base64')
}

export default {
  setServiceID,
  generateKeys,
  sign,
  cancelSigning,
  isKeyHardwareBacked,
  computeCoordinates,
  ECCError,
  ErrorCode,
  verify,
};
