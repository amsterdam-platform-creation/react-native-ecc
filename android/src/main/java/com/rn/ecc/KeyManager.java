package com.rn.ecc;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.KeyInfo;
import android.util.Base64;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyFactory;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.interfaces.ECPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.util.UUID;

public class KeyManager {
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final int KEY_SIZE = 256;
    private static final String ALGORITHM = "NONEwithECDSA";

    private SharedPreferences sharedPreferences;

    public KeyManager(SharedPreferences sharedPreferences) {
        this.sharedPreferences = sharedPreferences;
    }

    /**
     * Generate public and private keys.
     *
     * The private key is stored in the KeyStore,
     */
    public String generateKeys(boolean restricted) throws
        InvalidAlgorithmParameterException,
        InvalidKeyException,
        NoSuchAlgorithmException,
        NoSuchProviderException {

        String keystoreAlias = UUID.randomUUID().toString();

        KeyPairGenerator keyPairGenerator = getKeyPairGenerator(keystoreAlias, restricted);
        KeyPair keyPair = keyPairGenerator.genKeyPair();

        ECPublicKey ecPublicKey = (ECPublicKey)keyPair.getPublic();
        String publicKey = EllipticCurveCryptography.getPublicKey(ecPublicKey);

        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(publicKey, keystoreAlias);
        editor.commit();

        return publicKey;
    }

    public boolean hasStoredKeysInKeystore(String publicKey) {
        try {
            String keystoreAlias = sharedPreferences.getString(publicKey, null);
            if (keystoreAlias == null) {
                return false;
            }

            KeyStore keystore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keystore.load(null);

            KeyStore.Entry entry = keystore.getEntry(keystoreAlias, null);

            return entry instanceof KeyStore.PrivateKeyEntry;
        } catch (Exception ex) {
            return false;
        }
    }

    public Signature getSigningSignature(String publicKey) throws
        CertificateException,
        NoSuchAlgorithmException,
        IOException,
        UnrecoverableKeyException,
        KeyStoreException,
        InvalidKeyException {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);

        String keyAlias = sharedPreferences.getString(publicKey, null);
        PrivateKey privateKey = (PrivateKey) keyStore.getKey(keyAlias, null);

        Signature signature = Signature.getInstance(ALGORITHM);
        signature.initSign(privateKey);
        return signature;
    }

    public boolean isKeyRestricted(String publicKey) throws
        CertificateException,
        NoSuchAlgorithmException,
        IOException,
        UnrecoverableKeyException,
        KeyStoreException,
        InvalidKeyException,
        InvalidKeySpecException,
        NoSuchProviderException {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);

        String keyAlias = sharedPreferences.getString(publicKey, null);
        PrivateKey privateKey = (PrivateKey) keyStore.getKey(keyAlias, null);

        KeyFactory factory = KeyFactory.getInstance(privateKey.getAlgorithm(), ANDROID_KEYSTORE);
        KeyInfo keyInfo = factory.getKeySpec(privateKey, KeyInfo.class);

        return keyInfo.isUserAuthenticationRequired();
    }

    public boolean isKeyHardwareBacked(String publicKey) throws
        CertificateException,
        NoSuchAlgorithmException,
        IOException,
        UnrecoverableKeyException,
        KeyStoreException,
        InvalidKeyException,
        InvalidKeySpecException,
        NoSuchProviderException {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);

        String keyAlias = sharedPreferences.getString(publicKey, null);
        PrivateKey privateKey = (PrivateKey) keyStore.getKey(keyAlias, null);

        KeyFactory factory = KeyFactory.getInstance(privateKey.getAlgorithm(), ANDROID_KEYSTORE);
        KeyInfo keyInfo = factory.getKeySpec(privateKey, KeyInfo.class);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            int secLevel = keyInfo.getSecurityLevel();
            return secLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT || secLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX;
        } else {
            return keyInfo.isInsideSecureHardware();
        }
    }

    public Signature getVerifyingSignature(String publicKey) throws
        NoSuchAlgorithmException,
        InvalidAlgorithmParameterException,
        InvalidKeySpecException,
        InvalidKeyException {
        byte[] publicKeyBytes = Base64.decode(publicKey, Base64.NO_WRAP);
        ECPublicKey ecPublicKey = EllipticCurveCryptography.decodeECPublicKey(publicKeyBytes);

        Signature signature = Signature.getInstance(ALGORITHM);
        signature.initVerify(ecPublicKey);

        return signature;
    }

    public String sign(String data, Signature signature) throws SignatureException {
        byte[] dataBytes = Base64.decode(data, Base64.NO_WRAP);
        signature.update(dataBytes);
        byte[] signedDataBytes = signature.sign();
        String signedData = Base64.encodeToString(signedDataBytes, Base64.NO_WRAP);
        return signedData;
    }

    public boolean verify(String data, String expected, Signature signature) throws GeneralSecurityException {
        byte[] dataBytes = Base64.decode(data, Base64.NO_WRAP);
        signature.update(dataBytes);
        byte[] expectedBytes = Base64.decode(expected, Base64.NO_WRAP);
        return signature.verify(expectedBytes);
    }

    private KeyPairGenerator getKeyPairGenerator(String keystoreAlias, boolean restricted) throws
        NoSuchAlgorithmException,
        InvalidAlgorithmParameterException,
        NoSuchProviderException
    {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE);
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(keystoreAlias, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY);

        if (restricted) {
            builder.setUserAuthenticationRequired(true); // Biometrics protection
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(0,  KeyProperties.AUTH_BIOMETRIC_STRONG); // | KeyProperties.AUTH_DEVICE_CREDENTIAL // Uncomment if fallback to passcode is allowed
            }
        }
        builder.setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512, KeyProperties.DIGEST_NONE);
        builder.setInvalidatedByBiometricEnrollment(false);
        builder.setKeySize(KEY_SIZE);

        keyPairGenerator.initialize(builder.build());
        return keyPairGenerator;
    }
}
