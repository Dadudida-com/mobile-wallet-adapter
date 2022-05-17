/*
 * Copyright (c) 2022 Solana Labs, Inc.
 */

package com.solana.mobilewalletadapter.walletlib.protocol;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.ArraySet;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.Size;

import com.solana.mobilewalletadapter.common.ProtocolContract;
import com.solana.mobilewalletadapter.common.protocol.CommitmentLevel;
import com.solana.mobilewalletadapter.common.protocol.PrivilegedMethod;
import com.solana.mobilewalletadapter.common.util.JsonPack;
import com.solana.mobilewalletadapter.common.util.NotifyOnCompleteFuture;
import com.solana.mobilewalletadapter.common.util.NotifyingCompletableFuture;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ExecutionException;

public class MobileWalletAdapterServer extends JsonRpc20Server {
    private static final String TAG = MobileWalletAdapterServer.class.getSimpleName();

    @NonNull
    private final Handler mHandler;
    @NonNull
    private final MethodHandlers mMethodHandlers;

    public interface MethodHandlers {
        void authorize(@NonNull AuthorizeRequest request);
        void signPayload(@NonNull SignPayloadRequest request);
        void signAndSendTransaction(@NonNull SignAndSendTransactionRequest request);
    }

    public MobileWalletAdapterServer(@NonNull Looper ioLooper, @NonNull MethodHandlers methodHandlers) {
        mHandler = new Handler(ioLooper);
        mMethodHandlers = methodHandlers;
    }

    @Override
    protected void dispatchRpc(@Nullable Object id,
                               @NonNull String method,
                               @Nullable Object params) {
        try {
            switch (method) {
                case ProtocolContract.METHOD_AUTHORIZE:
                    handleAuthorize(id, params);
                    break;
                case ProtocolContract.METHOD_SIGN_TRANSACTION:
                    handleSignPayload(id, params, SignPayloadRequest.Type.Transaction);
                    break;
                case ProtocolContract.METHOD_SIGN_MESSAGE:
                    handleSignPayload(id, params, SignPayloadRequest.Type.Message);
                    break;
                case ProtocolContract.METHOD_SIGN_AND_SEND_TRANSACTION:
                    handleSignAndSendTransaction(id, params);
                    break;
                default:
                    handleRpcError(id, JsonRpc20Server.ERROR_METHOD_NOT_FOUND, "method '" +
                            method + "' not available", null);
                    break;
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed sending response for id=" + id, e);
        }
    }

    private static abstract class RequestFuture<T> extends NotifyingCompletableFuture<T> {
        @Nullable
        public final Object id;

        public RequestFuture(@NonNull Handler handler,
                             @Nullable Object id) {
            super(handler);
            this.id = id;
        }
    }

    // =============================================================================================
    // authorize
    // =============================================================================================

    private void handleAuthorize(@Nullable Object id, @Nullable Object params) throws IOException {
        if (!(params instanceof JSONObject)) {
            handleRpcError(id, ERROR_INVALID_PARAMS, "params must be either a JSONObject", null);
            return;
        }

        final JSONObject o = (JSONObject) params;

        final JSONObject ident = o.optJSONObject(ProtocolContract.PARAMETER_IDENTITY);
        final Uri identityUri;
        final Uri iconUri;
        final String identityName;
        if (ident != null) {
            identityUri = ident.has(ProtocolContract.PARAMETER_IDENTITY_URI) ?
                    Uri.parse(ident.optString(ProtocolContract.PARAMETER_IDENTITY_URI)) : null;
            if (identityUri != null && (!identityUri.isAbsolute() || !identityUri.isHierarchical())) {
                handleRpcError(id, ERROR_INVALID_PARAMS, "When specified, identity.uri must be an absolute, hierarchical URI", null);
                return;
            }
            iconUri = ident.has(ProtocolContract.PARAMETER_IDENTITY_ICON) ?
                    Uri.parse(ident.optString(ProtocolContract.PARAMETER_IDENTITY_ICON)) : null;
            if (iconUri != null && !iconUri.isRelative()) {
                handleRpcError(id, ERROR_INVALID_PARAMS, "When specified, identity.icon must be a relative URI", null);
                return;
            }
            identityName = ident.has(ProtocolContract.PARAMETER_IDENTITY_NAME) ?
                    ident.optString(ProtocolContract.PARAMETER_IDENTITY_NAME) : null;
            if (identityName != null && identityName.isEmpty()) {
                handleRpcError(id, ERROR_INVALID_PARAMS, "When specified, identity.name must be a non-empty string", null);
                return;
            }
        } else {
            identityUri = null;
            iconUri = null;
            identityName = null;
        }

        final JSONArray pm = o.optJSONArray(ProtocolContract.PARAMETER_PRIVILEGED_METHODS);
        final int numPrivilegedMethods = (pm != null) ? pm.length() : 0;
        if (numPrivilegedMethods == 0) {
            handleRpcError(id, ERROR_INVALID_PARAMS, "privileged_methods must be a non-empty array of method names", null);
            return;
        }
        final ArraySet<PrivilegedMethod> privilegedMethods = new ArraySet<>(numPrivilegedMethods);
        for (int i = 0; i < numPrivilegedMethods; i++) {
            final String methodName = pm.optString(i);
            final PrivilegedMethod method = PrivilegedMethod.fromMethodName(methodName);
            if (method == null) {
                handleRpcError(id, ERROR_INVALID_PARAMS, "privileged_methods contains unknown method name '" + methodName + "'", null);
                return;
            }
        }

        final AuthorizeRequest request = new AuthorizeRequest(mHandler, id, identityUri, iconUri, identityName, privilegedMethods);
        request.notifyOnComplete(this::onAuthorizeComplete);
        mMethodHandlers.authorize(request);
    }

    private void onAuthorizeComplete(@NonNull NotifyOnCompleteFuture<AuthorizeResult> future) {
        final AuthorizeRequest request = (AuthorizeRequest) future;

        try {
            final AuthorizeResult result;
            try {
                result = request.get();
            } catch (ExecutionException e) {
                final Throwable cause = e.getCause();
                if (cause instanceof RequestDeclinedException) {
                    handleRpcError(request.id, ProtocolContract.ERROR_AUTHORIZATION_FAILED, "authorize request declined", null);
                } else {
                    handleRpcError(request.id, ERROR_INTERNAL, "Error while processing authorize request", null);
                }
                return;
            } catch (InterruptedException e) {
                throw new RuntimeException("Should never occur!");
            }

            assert(result != null); // checked in AuthorizeRequest.complete()

            final JSONObject o = new JSONObject();
            try {
                o.put(ProtocolContract.RESULT_AUTH_TOKEN, "42"); // TODO: generate real auth token
                o.put(ProtocolContract.RESULT_PUBLIC_KEY, "4242424242"); // TODO: real public key
                o.put(ProtocolContract.RESULT_WALLET_URI_BASE, result.walletUriBase); // OK if null
            } catch (JSONException e) {
                throw new RuntimeException("Failed preparing authorize response", e);
            }

            handleRpcResult(request.id, o);
        } catch (IOException e) {
            Log.e(TAG, "Failed sending response for id=" + request.id, e);
        }
    }

    public static class AuthorizeRequest extends RequestFuture<AuthorizeResult> {
        @Nullable
        public final Uri identityUri;
        @Nullable
        public final Uri iconUri;
        @Nullable
        public final String identityName;
        @NonNull
        public final Set<PrivilegedMethod> privilegedMethods;

        private AuthorizeRequest(@NonNull Handler handler,
                                 @Nullable Object id,
                                 @Nullable Uri identityUri,
                                 @Nullable Uri iconUri,
                                 @Nullable String identityName,
                                 @NonNull Set<PrivilegedMethod> privilegedMethods) {
            super(handler, id);
            this.identityUri = identityUri;
            this.iconUri = iconUri;
            this.identityName = identityName;
            this.privilegedMethods = privilegedMethods;
        }

        @Override
        public boolean complete(@Nullable AuthorizeResult result) {
            if (result == null) {
                throw new IllegalArgumentException("A non-null result must be provided");
            }
            return super.complete(result);
        }

        public boolean completeWithDecline() {
            return completeExceptionally(new RequestDeclinedException("authorize request declined"));
        }

        @NonNull
        @Override
        public String toString() {
            return "AuthorizeRequest{" +
                    "id=" + id +
                    ", identityUri=" + identityUri +
                    ", iconUri=" + iconUri +
                    ", identityName='" + identityName + '\'' +
                    ", privilegedMethods=" + privilegedMethods +
                    '/' + super.toString() +
                    '}';
        }
    }

    public static class AuthorizeResult {
        @Nullable
        public final Uri walletUriBase;

        public AuthorizeResult(@Nullable Uri walletUriBase) {
            this.walletUriBase = walletUriBase;
        }
    }

    // =============================================================================================
    // sign_* common
    // =============================================================================================

    private static abstract class SignRequest<T extends SignResult> extends RequestFuture<T> {
        @NonNull
        public final String authToken;

        @NonNull
        @Size(min = 1)
        public final byte[][] payloads;

        private SignRequest(@NonNull Handler handler,
                            @Nullable Object id,
                            @NonNull String authToken,
                            @NonNull @Size(min = 1) byte[][] payloads) {
            super(handler, id);
            this.authToken = authToken;
            this.payloads = payloads;
        }

        @Override
        public boolean complete(@Nullable T result) {
            if (result == null) {
                throw new IllegalArgumentException("A non-null result must be provided");
            } else if (result.getNumResults() != payloads.length) {
                throw new IllegalArgumentException("Number of signed results does not match the number of requested signatures");
            }

            return super.complete(result);
        }

        public boolean completeWithDecline() {
            return completeExceptionally(new RequestDeclinedException("sign request declined"));
        }

        public boolean completeWithReauthorizationRequired() {
            return completeExceptionally(new ReauthorizationRequiredException("auth_token requires reauthorization"));
        }

        public boolean completeWithAuthTokenNotValid() {
            return completeExceptionally(new AuthTokenNotValidException("auth_token not valid for signing of this payload"));
        }

        public boolean completeWithInvalidPayloads(@NonNull @Size(min = 1) boolean[] valid) {
            if (valid.length != payloads.length) {
                throw new IllegalArgumentException("Number of valid payload entries does not match the number of requested signatures");
            }
            return completeExceptionally(new InvalidPayloadException("One or more invalid payloads provided", valid));
        }

        @NonNull
        @Override
        public String toString() {
            return "SignRequest{" +
                    "id=" + id +
                    ", authToken='" + authToken + '\'' +
                    ", payloads=" + Arrays.toString(payloads) +
                    ", super=" + super.toString() +
                    '}';
        }
    }

    public interface SignResult {
        int getNumResults();
    }

    public static class SignPayloadRequest extends SignRequest<SignedPayloadResult> {
        public enum Type { Transaction, Message }

        @NonNull
        public final Type type;

        protected SignPayloadRequest(@NonNull Handler handler,
                                     @Nullable Object id,
                                     @NonNull Type type,
                                     @NonNull String authToken,
                                     @NonNull byte[][] payloads) {
            super(handler, id, authToken, payloads);
            this.type = type;
        }

        @NonNull
        @Override
        public String toString() {
            return "SignPayloadRequest{" +
                    "type=" + type +
                    ", super=" + super.toString() +
                    '}';
        }
    }

    public static class SignedPayloadResult implements SignResult {
        @NonNull
        @Size(min = 1)
        public final byte[][] signedPayloads;

        public SignedPayloadResult(@NonNull @Size(min = 1) byte[][] signedPayloads) {
            this.signedPayloads = signedPayloads;
        }

        @Override
        public int getNumResults() {
            return signedPayloads.length;
        }

        @NonNull
        @Override
        public String toString() {
            return "SignedPayloadResult{signedPayloads=" + Arrays.toString(signedPayloads) + '}';
        }
    }

    @NonNull
    @Size(min = 1)
    private static byte[][] unpackPayloadsArray(@NonNull JSONObject jo) {
        final JSONArray payloadsArray = jo.optJSONArray(ProtocolContract.PARAMETER_PAYLOADS);
        if (payloadsArray == null) {
            throw new IllegalArgumentException("request must contain an array of payloads to sign");
        }
        final int numPayloads = payloadsArray.length();
        if (numPayloads == 0) {
            throw new IllegalArgumentException("request must contain at least one payload to sign");
        }

        final byte[][] payloads;
        try {
            payloads = JsonPack.unpackBase64UrlArrayToByteArrays(payloadsArray);
        } catch (JSONException e) {
            throw new IllegalArgumentException("payloads must be an array of base64url-encoded Strings");
        }

        return payloads;
    }

    private void handleSignPayload(@Nullable Object id,
                                   @Nullable Object params,
                                   @NonNull SignPayloadRequest.Type type)
            throws IOException {
        if (!(params instanceof JSONObject)) {
            handleRpcError(id, ERROR_INVALID_PARAMS, "params must be either a JSONObject", null);
            return;
        }

        final JSONObject o = (JSONObject) params;

        final String authToken = o.optString(ProtocolContract.PARAMETER_AUTH_TOKEN);
        if (authToken.isEmpty()) {
            handleRpcError(id, ERROR_INVALID_PARAMS, "request must contain an auth_token", null);
            return;
        }

        final byte[][] payloads;
        try {
            payloads = unpackPayloadsArray(o);
        } catch (IllegalArgumentException e) {
            handleRpcError(id, ERROR_INVALID_PARAMS, "request contains an invalid payloads entry", null);
            return;
        }

        final SignPayloadRequest request = new SignPayloadRequest(mHandler, id, type, authToken, payloads);
        request.notifyOnComplete(this::onSignPayloadComplete);
        mMethodHandlers.signPayload(request);
    }

    private void onSignPayloadComplete(@NonNull NotifyOnCompleteFuture<SignedPayloadResult> future) {
        final SignPayloadRequest request = (SignPayloadRequest) future;

        try {
            final SignedPayloadResult result;
            try {
                result = request.get();
            } catch (ExecutionException e) {
                final Throwable cause = e.getCause();
                if (cause instanceof RequestDeclinedException) {
                    handleRpcError(request.id, ProtocolContract.ERROR_NOT_SIGNED, "sign request declined", null);
                } else if (cause instanceof ReauthorizationRequiredException) {
                    handleRpcError(request.id, ProtocolContract.ERROR_REAUTHORIZE, "auth_token requires reauthorization", null);
                } else if (cause instanceof AuthTokenNotValidException) {
                    handleRpcError(request.id, ProtocolContract.ERROR_AUTHORIZATION_FAILED, "auth_token not valid for signing", null);
                } else if (cause instanceof InvalidPayloadException) {
                    handleRpcError(request.id, ProtocolContract.ERROR_INVALID_PAYLOAD, "payload invalid for signing",
                            createInvalidPayloadData(((InvalidPayloadException) cause).valid));
                } else {
                    handleRpcError(request.id, ERROR_INTERNAL, "Error while processing sign request", null);
                }
                return;
            } catch (InterruptedException e) {
                throw new RuntimeException("Should never occur!");
            }

            assert(result != null); // checked in SignPayloadRequest.complete()
            assert(result.signedPayloads.length == request.payloads.length); // checked in SignPayloadRequest.complete()

            final JSONArray signedPayloads = JsonPack.packByteArraysToBase64UrlArray(result.signedPayloads);
            final JSONObject o = new JSONObject();
            try {
                o.put(ProtocolContract.RESULT_SIGNED_PAYLOADS, signedPayloads);
            } catch (JSONException e) {
                throw new RuntimeException("Failed preparing sign response", e);
            }

            handleRpcResult(request.id, o);
        } catch (IOException e) {
            Log.e(TAG, "Failed sending response for id=" + request.id, e);
        }
    }

    @NonNull
    private String createInvalidPayloadData(@NonNull @Size(min = 1) boolean[] valid) {
        final JSONArray arr = JsonPack.packBooleans(valid);
        final JSONObject o = new JSONObject();
        try {
            o.put(ProtocolContract.DATA_INVALID_PAYLOAD_VALID, arr);
        } catch (JSONException e) {
            throw new RuntimeException("Failed constructing invalid payload data", e);
        }

        return o.toString();
    }

    // =============================================================================================
    // sign_and_send_transaction
    // TODO: can we do a better job of merging this with sign_* above?
    // =============================================================================================

    public static class SignAndSendTransactionRequest extends SignRequest<SignatureResult> {
        @NonNull
        public final CommitmentLevel commitmentLevel;

        private SignAndSendTransactionRequest(@NonNull Handler handler,
                                              @Nullable Object id,
                                              @NonNull String authToken,
                                              @NonNull @Size(min = 1) byte[][] transactions,
                                              @NonNull CommitmentLevel commitmentLevel) {
            super(handler, id, authToken, transactions);
            this.commitmentLevel = commitmentLevel;
        }

        public boolean completeWithNotCommitted(@NonNull @Size(min = 1) byte[][] signatures,
                                                @NonNull @Size(min = 1) boolean[] committed) {
            if (signatures.length != payloads.length) {
                throw new IllegalArgumentException("Number of signatures does not match the number of transactions");
            } else if (committed.length != payloads.length) {
                throw new IllegalArgumentException("Number of committed values does not match the number of transactions");
            }
            return completeExceptionally(new NotCommittedException("One or more transactions did not reach the requested commitment level", signatures, committed));
        }

        @NonNull
        @Override
        public String toString() {
            return "SignAndSendTransactionRequest{" +
                    "commitmentLevel=" + commitmentLevel +
                    ", super=" + super.toString() +
                    '}';
        }
    }

    public static class SignatureResult implements SignResult {
        @NonNull
        @Size(min = 1)
        public final byte[][] signatures;

        public SignatureResult(@NonNull @Size(min = 1) byte[][] signatures) {
            this.signatures = signatures;
        }

        @Override
        public int getNumResults() {
            return signatures.length;
        }

        @NonNull
        @Override
        public String toString() {
            return "SignatureResult{signedPayloads=" + Arrays.toString(signatures) + '}';
        }
    }

    private void handleSignAndSendTransaction(@Nullable Object id, @Nullable Object params)
            throws IOException {
        if (!(params instanceof JSONObject)) {
            handleRpcError(id, ERROR_INVALID_PARAMS, "params must be either a JSONObject", null);
            return;
        }

        final JSONObject o = (JSONObject) params;

        final String authToken = o.optString(ProtocolContract.PARAMETER_AUTH_TOKEN);
        if (authToken.isEmpty()) {
            handleRpcError(id, ERROR_INVALID_PARAMS, "request must contain an auth_token", null);
            return;
        }

        final byte[][] payloads;
        try {
            payloads = unpackPayloadsArray(o);
        } catch (IllegalArgumentException e) {
            handleRpcError(id, ERROR_INVALID_PARAMS, "request contains an invalid payloads entry", null);
            return;
        }

        final String commitmentLevelStr = o.optString(ProtocolContract.PARAMETER_COMMITMENT);
        final CommitmentLevel commitmentLevel = CommitmentLevel.fromCommitmentLevelString(
                commitmentLevelStr);
        if (commitmentLevel == null) {
            handleRpcError(id, ERROR_INVALID_PARAMS, "request contains an invalid commitment_level", null);
            return;
        }

        final SignAndSendTransactionRequest request = new SignAndSendTransactionRequest(
                mHandler, id, authToken, payloads, commitmentLevel);
        request.notifyOnComplete(this::onSignAndSendTransactionComplete);
        mMethodHandlers.signAndSendTransaction(request);
    }

    private void onSignAndSendTransactionComplete(@NonNull NotifyOnCompleteFuture<SignatureResult> future) {
        final SignAndSendTransactionRequest request = (SignAndSendTransactionRequest) future;

        try {
            final SignatureResult result;
            try {
                result = request.get();
            } catch (ExecutionException e) {
                final Throwable cause = e.getCause();
                if (cause instanceof RequestDeclinedException) {
                    handleRpcError(request.id, ProtocolContract.ERROR_NOT_SIGNED, "sign request declined", null);
                } else if (cause instanceof ReauthorizationRequiredException) {
                    handleRpcError(request.id, ProtocolContract.ERROR_REAUTHORIZE, "auth_token requires reauthorization", null);
                } else if (cause instanceof AuthTokenNotValidException) {
                    handleRpcError(request.id, ProtocolContract.ERROR_AUTHORIZATION_FAILED, "auth_token not valid for signing", null);
                } else if (cause instanceof InvalidPayloadException) {
                    final InvalidPayloadException e2 = (InvalidPayloadException) cause;
                    handleRpcError(request.id, ProtocolContract.ERROR_INVALID_PAYLOAD, "payload invalid for signing",
                            createInvalidPayloadData(e2.valid));
                } else if (cause instanceof NotCommittedException) {
                    final NotCommittedException e2 = (NotCommittedException) cause;
                    handleRpcError(request.id, ProtocolContract.ERROR_NOT_COMMITTED, "transaction not committed",
                            createNotCommittedData(e2.signatures, e2.committed));
                } else {
                    handleRpcError(request.id, ERROR_INTERNAL, "Error while processing sign request", null);
                }
                return;
            } catch (InterruptedException e) {
                throw new RuntimeException("Should never occur!");
            }

            assert(result != null); // checked in SignPayloadRequest.complete()
            assert(result.signatures.length == request.payloads.length); // checked in SignPayloadRequest.complete()

            final JSONArray signatures = JsonPack.packByteArraysToBase64UrlArray(result.signatures);
            final JSONObject o = new JSONObject();
            try {
                o.put(ProtocolContract.RESULT_SIGNATURES, signatures);
            } catch (JSONException e) {
                throw new RuntimeException("Failed constructing sign response", e);
            }

            handleRpcResult(request.id, o);
        } catch (IOException e) {
            Log.e(TAG, "Failed sending response for id=" + request.id, e);
        }
    }

    @NonNull
    private String createNotCommittedData(@NonNull @Size(min = 1) byte[][] signatures,
                                          @NonNull @Size(min = 1) boolean[] committed) {
        final JSONArray signaturesArr = JsonPack.packByteArraysToBase64UrlArray(signatures);
        final JSONArray committedArr = JsonPack.packBooleans(committed);
        final JSONObject o = new JSONObject();
        try {
            o.put(ProtocolContract.DATA_NOT_COMMITTED_SIGNATURES, signaturesArr);
            o.put(ProtocolContract.DATA_NOT_COMMITTED_COMMITMENT, committedArr);
        } catch (JSONException e) {
            throw new RuntimeException("Failed constructing not committed data", e);
        }

        return o.toString();
    }

    // =============================================================================================
    // Common exceptions
    // =============================================================================================

    private static abstract class MobileWalletAdapterServerException extends Exception {
        MobileWalletAdapterServerException(@NonNull String m) { super(m); }
    }

    private static class RequestDeclinedException extends MobileWalletAdapterServerException {
        RequestDeclinedException(@NonNull String m) { super(m); }
    }

    private static class ReauthorizationRequiredException extends MobileWalletAdapterServerException {
        public ReauthorizationRequiredException(@NonNull String m) { super(m); }
    }

    private static class AuthTokenNotValidException extends MobileWalletAdapterServerException {
        public AuthTokenNotValidException(@NonNull String m) { super (m); }
    }

    private static class InvalidPayloadException extends MobileWalletAdapterServerException {
        @NonNull
        @Size(min = 1)
        public final boolean[] valid;

        private InvalidPayloadException(@NonNull String m,
                                        @NonNull @Size(min = 1) boolean[] valid) {
            super(m);
            this.valid = valid;
        }

        @Nullable
        @Override
        public String getMessage() {
            return super.getMessage() + "/valid=" + Arrays.toString(valid);
        }
    }

    private static class NotCommittedException extends MobileWalletAdapterServerException {
        @NonNull
        @Size(min = 1)
        public final byte[][] signatures;

        @NonNull
        @Size(min = 1)
        public final boolean[] committed;

        private NotCommittedException(@NonNull String m,
                                      @NonNull @Size(min = 1) byte[][] signatures,
                                      @NonNull @Size(min = 1) boolean[] committed) {
            super(m);
            this.signatures = signatures;
            this.committed = committed;
        }

        @Nullable
        @Override
        public String getMessage() {
            return super.getMessage() +
                    "/signatures=" + Arrays.toString(signatures) +
                    "/committed=" + Arrays.toString(committed);
        }
    }
}