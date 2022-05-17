/*
 * Copyright (c) 2022 Solana Labs, Inc.
 */

package com.solana.mobilewalletadapter.walletlib.association;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solana.mobilewalletadapter.common.AssociationContract;
import com.solana.mobilewalletadapter.walletlib.protocol.MobileWalletAdapterServer;
import com.solana.mobilewalletadapter.walletlib.scenario.Scenario;

public abstract class AssociationUri {
    @NonNull
    public final Uri uri;

    @NonNull
    public final String associationToken;

    protected AssociationUri(@NonNull Uri uri) {
        this.uri = uri;
        validate(uri);
        associationToken = parseAssociationToken(uri);
    }

    private static void validate(@NonNull Uri uri) {
        if (!uri.isHierarchical()) {
            throw new IllegalArgumentException("uri must be hierarchical");
        }
    }

    @NonNull
    public abstract Scenario createScenario(@NonNull Scenario.Callbacks callbacks,
                                            @NonNull MobileWalletAdapterServer.MethodHandlers methodHandlers);

    @NonNull
    private static String parseAssociationToken(@NonNull Uri uri) {
        final String associationToken = uri.getQueryParameter(
                AssociationContract.PARAMETER_ASSOCIATION_TOKEN);
        if (associationToken == null || associationToken.isEmpty()) {
            throw new IllegalArgumentException("association token must be provided");
        }

        return associationToken;
    }

    @Nullable
    public static AssociationUri parse(@NonNull Uri uri) {
        try {
            return new LocalAssociationUri(uri);
        } catch (IllegalArgumentException ignored) {}

        try {
            return new RemoteAssociationUri(uri);
        } catch (IllegalArgumentException ignored) {}

        return null;
    }
}