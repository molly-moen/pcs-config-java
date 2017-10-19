package com.microsoft.azure.iotsolutions.uiconfig.services.helpers;

import com.microsoft.azure.iotsolutions.uiconfig.services.exceptions.*;
import com.microsoft.azure.iotsolutions.uiconfig.services.external.IStorageAdapterClient;
import com.microsoft.azure.iotsolutions.uiconfig.services.external.ValueApiModel;
import play.libs.Json;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Function;

public class StorageWriteLock<T> {

    private final String collectionId;
    private final String key;
    private final IStorageAdapterClient client;
    private final BiConsumer<T, Boolean> setLockFlagAction;
    private final Function<ValueApiModel, Boolean> testLockFunc;

    private Class<T> type;
    private T lastValue;
    private String lastETag;

    public StorageWriteLock(
            Class<T> type,
            IStorageAdapterClient client,
            String collectionId,
            String key,
            BiConsumer<T, Boolean> setLockFlagAction,
            Function<ValueApiModel, Boolean> testLockFunc) {
        this.client = client;
        this.collectionId = collectionId;
        this.key = key;
        this.setLockFlagAction = setLockFlagAction;
        this.testLockFunc = testLockFunc;
        this.lastETag = null;
        this.type = type;
    }

    private CompletionStage<String> UpdateValueAsync(T value, String etag) throws BaseException {
        return this.client.updateAsync(
                this.collectionId,
                this.key,
                Json.stringify(Json.toJson(value)),
                etag).thenApplyAsync(m -> m.getETag());
    }

    public CompletionStage<Optional<Boolean>> TryLockAsync() throws ResourceOutOfDateException, ExternalDependencyException {
        if (this.lastETag != null) {
            throw new ResourceOutOfDateException("Lock has already been acquired");
        }

        ValueApiModel model = null;

        try {
            model = this.client.getAsync(this.collectionId, this.key).toCompletableFuture().get();
        } catch (ResourceNotFoundException e) {
            // Nothing to do
        } catch (InterruptedException | ExecutionException | BaseException e) {
            throw new ExternalDependencyException(String.format("unexcepted error to get %s,%s", this.collectionId, this.key));
        }


        if (!this.testLockFunc.apply(model)) {
            return CompletableFuture.supplyAsync(() -> Optional.of(false));
        }
        try {
            this.lastValue = model == null ? type.newInstance() : Json.fromJson(Json.parse(model.getData()), type);
        } catch (InstantiationException | IllegalAccessException e) {
            throw new ExternalDependencyException("falied to newInstance type" + type.getTypeName());
        }
        this.setLockFlagAction.accept(this.lastValue, true);

        try {
            return this.UpdateValueAsync(this.lastValue, model == null ? null : model.getETag()).thenAccept(m -> {
                this.lastETag = m;
            }).thenApplyAsync((m) -> Optional.of(true));
        } catch (BaseException e) {
            return CompletableFuture.supplyAsync(() -> Optional.empty());
        }
    }

    public CompletionStage ReleaseAsync() throws ResourceOutOfDateException {
        if (this.lastETag == null) {
            throw new ResourceOutOfDateException("Lock was not acquired yet");
        }

        this.setLockFlagAction.accept(this.lastValue, false);

        try {
            return this.UpdateValueAsync(this.lastValue, this.lastETag).thenAcceptAsync(m -> {
            });
        } catch (BaseException e) {
            // Nothing to do
        }
        this.lastETag = null;
        return CompletableFuture.runAsync(() -> {
        });
    }

    public CompletionStage<Boolean> WriteAndReleaseAsync(T newValue) throws ResourceOutOfDateException {
        if (this.lastETag == null) {
            throw new ResourceOutOfDateException("Lock was not acquired yet");
        }

        this.setLockFlagAction.accept(newValue, false);
        try {
            return this.UpdateValueAsync(newValue, this.lastETag).thenApplyAsync(m -> {
                this.lastETag = null;
                return true;
            });
        } catch (BaseException e) {
            return CompletableFuture.supplyAsync(() -> false);
        }
    }
}
