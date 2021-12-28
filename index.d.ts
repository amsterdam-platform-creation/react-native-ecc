declare module 'react-native-ecc' {
  type PublicKey = string

  type SignArgs = {
    promptTitle: string
    promptMessage: string
    promptCancel: string
  }

  type PublicKeyPoints = {
    x: string
    y: string
  }

  export enum ErrorCode {
    Canceled = 'canceled',
    BiometryNotAvailable = 'biometry-not-available',
    BiometryNotEnrolled = 'biometry-not-enrolled',
    LockoutTemporarily = 'lockout-temporarily',
    LockoutPermanent = 'lockout-permanent',
    NonCompliantPrompt = 'non-compliant-prompt',
    Generic = 'generic',
  }

  export class ECCError extends Error {
    errorCode: ErrorCode
    nativeCode: string

    constructor(errorCode: ErrorCode, nativeCode: string)
  }

  function setServiceID(string): void
  function generateKeys(restricted?: boolean): Promise<PublicKey>
  function sign(publicKey: PublicKey, data: string, args: SignArgs): Promise<string>
  function cancelSigning(): Promise<void>
  function computeCoordinates(publicKey: PublicKey): PublicKeyPoints
  function verify(publicKey: PublicKey, data: string, sig: string): Promise<boolean>
  function isKeyHardwareBacked(publicKey: PublicKey): Promise<boolean>
}
