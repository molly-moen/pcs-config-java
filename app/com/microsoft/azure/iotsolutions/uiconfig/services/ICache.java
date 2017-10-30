// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.azure.iotsolutions.uiconfig.services;

import com.google.inject.ImplementedBy;
import com.microsoft.azure.iotsolutions.uiconfig.services.exceptions.ExternalDependencyException;
import com.microsoft.azure.iotsolutions.uiconfig.services.exceptions.ResourceOutOfDateException;
import com.microsoft.azure.iotsolutions.uiconfig.services.models.CacheValue;

import java.util.concurrent.CompletionStage;

@ImplementedBy(Cache.class)
public interface ICache {
    CompletionStage<CacheValue> getCacheAsync();

    CompletionStage<CacheValue> setCacheAsync(CacheValue cache) throws ExternalDependencyException;

    CompletionStage rebuildCacheAsync(boolean force) throws ResourceOutOfDateException, ExternalDependencyException;

    default CompletionStage rebuildCacheAsync() throws ExternalDependencyException, ResourceOutOfDateException {
        return rebuildCacheAsync(false);
    }
}
