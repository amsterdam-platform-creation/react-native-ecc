package com.rn.ecc;

import android.content.Context;
import android.os.Build;

import androidx.biometric.BiometricPrompt;
import androidx.biometric.BiometricPrompt.PromptInfo;
import androidx.fragment.app.FragmentActivity;

import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.UiThreadUtil;

import java.security.Signature;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;


public class ECCModule extends ReactContextBaseJavaModule {
    private static final String KEY_TO_ALIAS_MAPPER = "key.to.alias.mapper";
    private final KeyManager keyManager;
    private BiometricPrompt biometricPrompt;

    // Triggered when invalid parameters have been given to the biometric prompt
    // (e.g., no prompt title).
    public static final int ERROR_INVALID_PROMPT_PARAMETERS = 1000;
    // Triggered when trying to sign and the biometric set changed.
    public static final int ERROR_INVALID_SIGNATURE = 1001;
    // Triggered by some OnePlus devices (that implement the biometric prompt
    // wrong) on soft failures (e.g., wrong fingerprint).
    public static final int ERROR_NON_COMPLIANT_PROMPT = 1002;
    public static final int ERROR_BIOMETRY_NOT_ENROLLED = 1003;

    public ECCModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.keyManager = new KeyManager(reactContext.getSharedPreferences(KEY_TO_ALIAS_MAPPER, Context.MODE_PRIVATE));
    }

    @Override
    public String getName() {
        return "RNECC";
    }

    @Override
    public Map<String, Object> getConstants() {
        final Map<String, Object> constants = new HashMap<>();
        constants.put("preHash", true);
        return constants;
    }

    @ReactMethod
    public void generateECPair(ReadableMap map, Callback function) {
        try {
            boolean restricted = map.getBoolean("restricted");
            String publicKey = keyManager.generateKeys(restricted);
            function.invoke(null, publicKey);
        } catch (Exception ex) {
            if (ex.toString().startsWith("java.security.InvalidAlgorithmParameterException: java.lang.IllegalStateException: At least one ")) {
                function.invoke(ERROR_BIOMETRY_NOT_ENROLLED, null);
            } else {
                function.invoke(ex.toString(), null);
            }
        }
    }

    @ReactMethod
    public void hasKey(final ReadableMap map, Callback function) {
        final String publicKey = map.getString("pub");
        function.invoke(null, keyManager.hasStoredKeysInKeystore(publicKey));
    }

    @ReactMethod
    public void sign(final ReadableMap map, final Callback function) {
        try {
            final String data = map.getString("hash");
            final String publicKey = map.getString("pub");
            final String message = map.getString("promptMessage");
            final String title = map.getString("promptTitle");
            final String cancel = map.getString("promptCancel");

            boolean restricted = keyManager.isKeyRestricted(publicKey);

            if (!restricted) {
                Signature signature = keyManager.getSigningSignature(publicKey);
                function.invoke(null, keyManager.sign(data, signature));
                return;
            }

            UiThreadUtil.runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            biometricPrompt = new BiometricPrompt(
                                (FragmentActivity) getCurrentActivity(),
                                Executors.newSingleThreadExecutor(),
                                new ECCAuthenticationCallback(keyManager, data, function)
                            );

                            PromptInfo.Builder builder = new PromptInfo.Builder()
                                .setTitle(title)
                                .setDescription(message);
                            // Uncomment if fallback to passcode is allowed
                            // if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                            //     builder.setDeviceCredentialAllowed(true);
                            // } else {
                                builder.setNegativeButtonText(cancel);
                            // }

                            Signature signature = keyManager.getSigningSignature(publicKey);
                            BiometricPrompt.CryptoObject cryptoObject = new BiometricPrompt.CryptoObject(signature);

                            biometricPrompt.authenticate(builder.build(), cryptoObject);
                        } catch (IllegalArgumentException ex) {
                            function.invoke(ERROR_INVALID_PROMPT_PARAMETERS, null);
                        } catch (Exception ex) {
                            function.invoke(ERROR_INVALID_SIGNATURE, null);
                        }
                    }
                });
        } catch (Exception ex) {
            function.invoke(ex.toString(), null);
        }

    }

    @ReactMethod
    public void verify(ReadableMap map, Callback function) {
        try {
            String data = map.getString("hash");
            String publicKey = map.getString("pub");
            String expected = map.getString("sig");

            Signature signature = keyManager.getVerifyingSignature(publicKey);
            function.invoke(null, keyManager.verify(data, expected, signature));
        } catch (Exception ex) {
            function.invoke(ex.toString(), null);
        }
    }

    @ReactMethod
    public void cancelSigning(ReadableMap map, Callback function) {
        cancelAuthentication();
    }

    @ReactMethod
    public void isKeyHardwareBacked(ReadableMap map, Callback function) {
        try {
            String publicKey = map.getString("pub");
            boolean result = keyManager.isKeyHardwareBacked(publicKey);
            function.invoke(null, result);
        } catch (Exception ex) {
            function.invoke(ex.toString(), null);
        }
    }

    private void cancelAuthentication() {
        try {
            if (biometricPrompt != null) {
                biometricPrompt.cancelAuthentication();
            }
        } catch (Exception ex) {
            // Do nothing.
        } finally {
            biometricPrompt = null;
        }
    }

    public class ECCAuthenticationCallback extends BiometricPrompt.AuthenticationCallback {
        // See: https://forums.oneplus.com/threads/oneplus-7-pro-fingerprint-biometricprompt-does-not-show.1035821/
        private final String[] ONEPLUS_MODELS_WITHOUT_BIOMETRIC_BUG = {
            "A0001", // OnePlus One
            "ONE A2001", "ONE A2003", "ONE A2005", // OnePlus 2
            "ONE E1001", "ONE E1003", "ONE E1005", // OnePlus X
            "ONEPLUS A3000", "ONEPLUS SM-A3000", "ONEPLUS A3003", // OnePlus 3
            "ONEPLUS A3010", // OnePlus 3T
            "ONEPLUS A5000", // OnePlus 5
            "ONEPLUS A5010", // OnePlus 5T
            "ONEPLUS A6000", "ONEPLUS A6003" // OnePlus 6
        };

        public boolean hasOnePlusBiometricBug() {
            return Build.BRAND.equalsIgnoreCase("oneplus") &&
                !Arrays.asList(ONEPLUS_MODELS_WITHOUT_BIOMETRIC_BUG).contains(Build.MODEL);
        }

        private final KeyManager keyManager;
        private final String data;
        private final Callback callback;
        private boolean onePlusWithBiometricBugFailure;

        // This boolean is meant to check if the invoke method has already been called.
        // This is to avoid crashes caused by a second call to the invoke method.
        private boolean didResolve;

        public ECCAuthenticationCallback(KeyManager keyManager, String data, Callback callback) {
            this.keyManager = keyManager;
            this.data = data;
            this.callback = callback;
            this.onePlusWithBiometricBugFailure = false;
        }

        @Override
        public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult authenticationResult) {
            super.onAuthenticationSucceeded(authenticationResult);
            if(didResolve) {
              return;
            }

            try {
                BiometricPrompt.CryptoObject cryptoObject = authenticationResult.getCryptoObject();
                Signature signature = cryptoObject.getSignature();
                String signedData = keyManager.sign(data, signature);
                callback.invoke(null, signedData);
            } catch (Exception ex) {
                callback.invoke(ECCModule.ERROR_INVALID_SIGNATURE, null);
            } finally {
                cancelAuthentication();
                onePlusWithBiometricBugFailure = false;
                didResolve = true;
            }
        }

        @Override
        public void onAuthenticationError(int errorCode, CharSequence errorCharSequence) {
            super.onAuthenticationError(errorCode, errorCharSequence);
            if(didResolve) {
              return;
            }

            if (this.onePlusWithBiometricBugFailure) {
                onePlusWithBiometricBugFailure = false;
                callback.invoke(ERROR_NON_COMPLIANT_PROMPT, null);
            } else {
                callback.invoke(errorCode, null);
            }

            // This method should be called automatically by Android but in some case ( Huawei ) is not.
            // This is to avoid crashes caused by a second call to the invoke method.
            cancelAuthentication();

            didResolve = true;
        }

        @Override
        public void onAuthenticationFailed() {
            super.onAuthenticationFailed();

            if (biometricPrompt != null && hasOnePlusBiometricBug()) {
                onePlusWithBiometricBugFailure = true;
                cancelAuthentication();
            }
        }
    }
}
