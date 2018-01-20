// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.azure.iotsolutions.uiconfig.services;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.microsoft.azure.iotsolutions.uiconfig.services.exceptions.*;
import com.microsoft.azure.iotsolutions.uiconfig.services.external.IIothubManagerServiceClient;
import com.microsoft.azure.iotsolutions.uiconfig.services.external.ISimulationServiceClient;
import com.microsoft.azure.iotsolutions.uiconfig.services.external.IStorageAdapterClient;
import com.microsoft.azure.iotsolutions.uiconfig.services.external.ValueApiModel;
import com.microsoft.azure.iotsolutions.uiconfig.services.helpers.StorageWriteLock;
import com.microsoft.azure.iotsolutions.uiconfig.services.models.CacheValue;
import com.microsoft.azure.iotsolutions.uiconfig.services.models.DeviceTwinName;
import com.microsoft.azure.iotsolutions.uiconfig.services.runtime.IServicesConfig;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import play.Logger;
import play.libs.Json;

import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Singleton
public class Cache implements ICache {

    private final IStorageAdapterClient storageClient;
    private final IIothubManagerServiceClient iotHubClient;
    private final ISimulationServiceClient simulationClient;
    private static final Logger.ALogger log = Logger.of(Cache.class);
    private final int cacheTTL;
    private final int rebuildTimeout;
    private final String CacheCollectionId = "cache";
    private final String CacheKey = "twin";
    private final List<String> cacheWhitelist;
    private static final String WHITELIST_TAG_PREFIX = "tags.";
    private static final String WHITELIST_REPORTED_PREFIX = "reported.";
    private static final long serviceQueryInterval = 10;

    @Inject
    public Cache(IStorageAdapterClient storageClient,
                 IIothubManagerServiceClient iotHubClient,
                 ISimulationServiceClient simulationClient,
                 IServicesConfig config) throws ExternalDependencyException {
        this.storageClient = storageClient;
        this.iotHubClient = iotHubClient;
        this.simulationClient = simulationClient;
        this.cacheTTL = config.getCacheTTL();
        this.rebuildTimeout = config.getCacheRebuildTimeout();
        this.cacheWhitelist = config.getCacheWhiteList();
    }

    @Override
    public CompletionStage<CacheValue> getCacheAsync() {
        try {
            return storageClient.getAsync(CacheCollectionId, CacheKey).thenApplyAsync(m ->
                    Json.fromJson(Json.parse(m.getData()), CacheValue.class)
            );
        } catch (Exception ex) {
            log.info(String.format("%s:%s not found.", CacheCollectionId, CacheKey), ex);
            return CompletableFuture.completedFuture(new CacheValue(new HashSet<>(), new HashSet<>()));
        }
    }

    @Override
    public CompletionStage<CacheValue> setCacheAsync(CacheValue cacheValuesToAdd) throws BaseException {
        if (cacheValuesToAdd.getReported() == null) {
            cacheValuesToAdd.setReported(new HashSet<>());
        }
        if (cacheValuesToAdd.getTags() == null) {
            cacheValuesToAdd.setTags(new HashSet<>());
        }
        String etag = null;
        while (true) {
            ValueApiModel model = this.getCurrentCacheFromStorage();
            if (model != null) {
                etag = model.getETag();
                CacheValue cacheServer = Json.fromJson(Json.parse(model.getData()), CacheValue.class);
                this.updateCacheValues(model, cacheValuesToAdd);
                if (cacheValuesToAdd.getTags().size() == cacheServer.getTags().size() && cacheValuesToAdd.getReported().size() == cacheServer.getReported().size()) {
                    return CompletableFuture.completedFuture(cacheValuesToAdd);
                }
            }

            String value = Json.stringify(Json.toJson(cacheValuesToAdd));
            try {
                return this.storageClient.updateAsync(CacheCollectionId, CacheKey, value, etag).thenApplyAsync(m ->
                        Json.fromJson(Json.parse(m.getData()), CacheValue.class)
                );
            } catch (ConflictingResourceException e) {
                log.info("SetCacheAsync Conflicted ");
                continue;
            }
        }
    }

    @Override
    public CompletionStage rebuildCacheAsync(boolean force) throws Exception {
        StorageWriteLock<CacheValue> lock = new StorageWriteLock<>(
                CacheValue.class,
                this.storageClient,
                CacheCollectionId,
                CacheKey,
                (c, b) -> c.setRebuilding(b),
                m -> this.needBuild(force, m));

        while (true) {
            Optional<Boolean> locked = this.lockCache(lock);
            if (locked == null) {
                this.log.warn("Cache rebuilding: lock failed due to conflict. Retry soon");
                Thread.sleep(this.serviceQueryInterval);
                continue;
            }
            if (!locked.get()) {
                return CompletableFuture.completedFuture(false);
            }
            // Build the cache content
            DeviceTwinName deviceTwinName = null;
            try {
                deviceTwinName = this.getDevicePropertyNames();
                if(deviceTwinName.isEmpty()) {
                    this.log.info("There is no property available to be cached");
                    lock.releaseAsync().toCompletableFuture().get();
                    return CompletableFuture.completedFuture(false);
                }
            } catch (Exception e) {
                this.log.warn(String.format("Some underlying service is not ready. Retry after %d seconds", this.serviceQueryInterval));
                lock.releaseAsync().toCompletableFuture().get();
                Thread.sleep(this.serviceQueryInterval);
                continue;
            }

            Boolean updated = this.writeAndUnlockCache(lock, deviceTwinName);

            if (updated) {
                return CompletableFuture.completedFuture(true);
            }

            this.log.warn("The cache failed to be written due to conflict. Retry soon");
        }
    }

    private boolean needBuild(boolean force, ValueApiModel valueApiModel) {
        if (force) {
            this.log.info("The cache will be rebuilt due to the force flag");
            return true;
        }

        if (valueApiModel == null) {
            this.log.info("The cache will be built because no cache was found");
            return true;
        }

        CacheValue cachedValue = Json.fromJson(Json.parse(valueApiModel.getData()), CacheValue.class);
        if (cachedValue.isEmpty()) {
            this.log.info("The cache will be rebuilt because the existing cache is empty");
            return true;
        }

        boolean rebuilding = cachedValue.isRebuilding();
        DateTimeFormatter formatter = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ssZZ");
        DateTime timestamp = formatter.parseDateTime(valueApiModel.getMetadata().get("$modified"));
        if (rebuilding) {
            if (timestamp.plusSeconds(this.rebuildTimeout).isBeforeNow()) {
                this.log.info("The cache will be rebuilt because the last rebuild timed out");
                return true;
            } else {
                this.log.info("Skipping the cache build because another process is already building it");
                return false;
            }
        } else {
            if (timestamp.plusSeconds(this.cacheTTL).isBeforeNow()) {
                this.log.info("The cache will be rebuild because the previous cache expired");
                return true;
            } else {
                this.log.info("Skipping the cache build because the current cache is not expired yet");
                return false;
            }
        }
    }

    private CompletionStage<DeviceTwinName> getValidNamesAsync() throws ExternalDependencyException {
        DeviceTwinName fullNameWhitelist = new DeviceTwinName(), prefixWhitelist = new DeviceTwinName();
        this.parseWhitelist(this.cacheWhitelist, fullNameWhitelist, prefixWhitelist);

        DeviceTwinName validNames = new DeviceTwinName(fullNameWhitelist.getTags(), fullNameWhitelist.getReportedProperties());

        if (!prefixWhitelist.getTags().isEmpty() || !prefixWhitelist.getReportedProperties().isEmpty()) {
            DeviceTwinName allNames = null;
            try {
                allNames = this.iotHubClient.getDeviceTwinNamesAsync().toCompletableFuture().get();
            } catch (InterruptedException | ExecutionException | URISyntaxException e) {
                String errorMessage = "failed to get deviceTwinNames";
                log.error(errorMessage, e);
                throw new ExternalDependencyException(errorMessage, e);
            }

            validNames.getTags().addAll(allNames.getTags().stream().
                    filter(m -> prefixWhitelist.getTags().stream().anyMatch(m::startsWith)).collect(Collectors.toSet()));

            validNames.getReportedProperties().addAll(allNames.getReportedProperties().stream().
                    filter(m -> prefixWhitelist.getReportedProperties().stream().anyMatch(m::startsWith)).collect(Collectors.toSet()));
        }

        return CompletableFuture.supplyAsync(() -> validNames);
    }

    private void parseWhitelist(List<String> whitelist, DeviceTwinName fullNameWhitelist, DeviceTwinName prefixWhitelist) {

        List<String> tags = whitelist.stream().filter(m -> m.startsWith(WHITELIST_TAG_PREFIX)).
                map(m -> m.substring(WHITELIST_TAG_PREFIX.length())).collect(Collectors.toList());

        List<String> reported = whitelist.stream().filter(m -> m.startsWith(WHITELIST_REPORTED_PREFIX)).
                map(m -> m.substring(WHITELIST_REPORTED_PREFIX.length())).collect(Collectors.toList());

        List<String> fixedTags = tags.stream().filter(m -> !m.endsWith("*")).collect(Collectors.toList());
        List<String> fixedReported = reported.stream().filter(m -> !m.endsWith("*")).collect(Collectors.toList());
        List<String> regexTags = tags.stream().filter(m -> m.endsWith("*")).
                map(m -> m.substring(0, m.length() - 1)).collect(Collectors.toList());

        List<String> regexReported = reported.stream().filter(m -> m.endsWith("*")).
                map(m -> m.substring(0, m.length() - 1)).collect(Collectors.toList());

        fullNameWhitelist.setTags(new HashSet<>(fixedTags));
        fullNameWhitelist.setReportedProperties(new HashSet<>(fixedReported));
        prefixWhitelist.setTags(new HashSet<>(regexTags));
        prefixWhitelist.setReportedProperties(new HashSet<>(regexReported));
    }

    private ValueApiModel getCurrentCacheFromStorage() throws ExternalDependencyException {
        try {
            return this.storageClient.getAsync(CacheCollectionId, CacheKey).toCompletableFuture().get();
        } catch (ResourceNotFoundException e) {
            log.info(String.format("SetCacheAsync %s:%s not found.", CacheCollectionId, CacheKey));
        } catch (InterruptedException | ExecutionException | BaseException e) {
            log.error(String.format("SetCacheAsync InterruptedException occurred in storageClient.getAsync(%s, %s).", CacheCollectionId, CacheKey));
            throw new ExternalDependencyException("SetCacheAsync failed");
        }
        return null;
    }

    private void updateCacheValues(ValueApiModel CacheFromStorage, CacheValue cacheValuesToAdd) {
        if (CacheFromStorage != null) {
            CacheValue cacheServer;
            try {
                cacheServer = Json.fromJson(Json.parse(CacheFromStorage.getData()), CacheValue.class);
            } catch (Exception e) {
                cacheServer = new CacheValue();
            }
            if (cacheServer.getTags() == null) {
                cacheServer.setTags(new HashSet<String>());
            }
            if (cacheServer.getReported() == null) {
                cacheServer.setReported(new HashSet<String>());
            }
            cacheValuesToAdd.getTags().addAll(cacheServer.getTags());
            cacheValuesToAdd.getReported().addAll(cacheServer.getReported());
        }
    }

    private Optional<Boolean> lockCache(StorageWriteLock<CacheValue> lock) throws ExternalDependencyException, ResourceOutOfDateException {
        try {
            return lock.tryLockAsync().toCompletableFuture().get();
        } catch (InterruptedException | ExecutionException e) {
            throw new ExternalDependencyException("failed to lock");
        }
    }

    private DeviceTwinName getDevicePropertyNames() throws ExternalDependencyException, URISyntaxException, ExecutionException, InterruptedException {
        CompletableFuture<DeviceTwinName> twinNamesTask = this.getValidNamesAsync().toCompletableFuture();
        CompletableFuture<HashSet<String>> simulationNamesTask = this.simulationClient.getDevicePropertyNamesAsync().toCompletableFuture();
        CompletableFuture.allOf(twinNamesTask, simulationNamesTask).get();
        DeviceTwinName twinNames = twinNamesTask.get();
        twinNames.getReportedProperties().addAll(simulationNamesTask.get());
        return twinNames;
    }

    private Boolean writeAndUnlockCache(StorageWriteLock<CacheValue> lock, DeviceTwinName twinNames) throws ExternalDependencyException, ResourceOutOfDateException {
        try {
            return lock.writeAndReleaseAsync(new CacheValue(twinNames.getTags(), twinNames.getReportedProperties())).toCompletableFuture().get();
        } catch (InterruptedException | ExecutionException e) {
            String errorMessage = String.format("failed to WriteAndRelease lock for %s,%s ", CacheCollectionId, CacheKey);
            this.log.error(errorMessage, e);
            throw new ExternalDependencyException(errorMessage);
        }
    }
}
