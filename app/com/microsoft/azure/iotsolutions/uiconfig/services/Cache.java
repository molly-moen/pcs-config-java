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
        // global setting is not recommend for application_onStart event, PLS refer here for details :https://www.playframework.com/documentation/2.6.x/GlobalSettings
        new Thread(() -> {
            try {
                Thread.sleep(10000);
                rebuildCacheAsync().toCompletableFuture().get();
            } catch (Exception e) {
                Logger.of(Seed.class).error("RebuildCacheAsync");
            }
        }).start();
    }

    @Override
    public CompletionStage<CacheValue> getCacheAsync() {
        try {
            return storageClient.getAsync(CacheCollectionId, CacheKey).thenApplyAsync(m ->
                    Json.fromJson(Json.parse(m.getData()), CacheValue.class)
            );
        } catch (Exception ex) {
            log.info(String.format("%s:%s not found.", CacheCollectionId, CacheKey));
            return CompletableFuture.supplyAsync(() -> new CacheValue(new HashSet<String>(), new HashSet<String>()));
        }
    }

    @Override
    public CompletionStage<CacheValue> setCacheAsync(CacheValue cache) throws ExternalDependencyException {
        if (cache.getReported() == null) {
            cache.setReported(new HashSet<String>());
        }
        if (cache.getTags() == null) {
            cache.setTags(new HashSet<String>());
        }
        String etag = null;
        while (true) {
            ValueApiModel model = null;
            try {
                model = this.storageClient.getAsync(CacheCollectionId, CacheKey).toCompletableFuture().get();
            } catch (ResourceNotFoundException e) {
                log.info(String.format("SetCacheAsync %s:%s not found.", CacheCollectionId, CacheKey));
            } catch (InterruptedException | ExecutionException | BaseException e) {
                log.error(String.format("SetCacheAsync InterruptedException occured in storageClient.getAsync(%s, %s).", CacheCollectionId, CacheKey));
                throw new ExternalDependencyException("SetCacheAsync failed");
            }
            if (model != null) {
                CacheValue cacheServer;
                try {
                    cacheServer = Json.fromJson(Json.parse(model.getData()), CacheValue.class);
                } catch (Exception e) {
                    cacheServer = new CacheValue();
                }
                if (cacheServer.getTags() == null) {
                    cacheServer.setTags(new HashSet<String>());
                }
                if (cacheServer.getReported() == null) {
                    cacheServer.setReported(new HashSet<String>());
                }
                cache.getTags().addAll(cacheServer.getTags());
                cache.getReported().addAll(cacheServer.getReported());
                etag = model.getETag();
                if (cache.getTags().size() == cacheServer.getTags().size() && cache.getReported().size() == cacheServer.getReported().size()) {
                    return CompletableFuture.supplyAsync(() -> cache);
                }
            }

            String value = Json.stringify(Json.toJson(cache));
            try {
                return this.storageClient.updateAsync(CacheCollectionId, CacheKey, value, etag).thenApplyAsync(m ->
                        Json.fromJson(Json.parse(m.getData()), CacheValue.class)
                );
            } catch (ConflictingResourceException e) {
                log.info("SetCacheAsync Conflicted ");
                continue;
            } catch (BaseException e) {
                throw new ExternalDependencyException("SetCacheAsync failed ");
            }
        }
    }

    @Override
    public CompletionStage rebuildCacheAsync(boolean force) throws ResourceOutOfDateException, ExternalDependencyException {
        {
            StorageWriteLock<CacheValue> lock = new StorageWriteLock<>(
                    CacheValue.class,
                    this.storageClient,
                    CacheCollectionId,
                    CacheKey,
                    (c, b) -> c.setRebuilding(b),
                    m -> this.needBuild(force, m));

            while (true) {
                Optional<Boolean> locked = null;
                try {
                    locked = lock.tryLockAsync().toCompletableFuture().get();
                } catch (InterruptedException | ExecutionException e) {
                    throw new ExternalDependencyException("failed to lock");
                }
                if (locked == null) {
                    this.log.warn("Cache rebuilding: lock failed due to conflict. Retry soon");
                    continue;
                }
                if (!locked.get()) {
                    return CompletableFuture.supplyAsync(() -> false);
                }
                // Build the cache content
                DeviceTwinName twinNames = null;
                try {
                    CompletableFuture<DeviceTwinName> twinNamesTask = this.getValidNamesAsync().toCompletableFuture();
                    CompletableFuture<HashSet<String>> simulationNamesTask = this.simulationClient.getDevicePropertyNamesAsync().toCompletableFuture();
                    CompletableFuture.allOf(twinNamesTask, simulationNamesTask).get();
                    twinNames = twinNamesTask.get();
                    twinNames.getReportedProperties().addAll(simulationNamesTask.get());
                } catch (Exception e) {
                    this.log.warn("Some underlying service is not ready. Retry after " + this.serviceQueryInterval);
                    try {
                        lock.releaseAsync().toCompletableFuture().get();
                        Thread.sleep(this.serviceQueryInterval);
                    } catch (Exception ex) {
                        throw new ExternalDependencyException("failed to release lock");
                    }
                    continue;
                }

                Boolean updated = false;
                try {
                    updated = lock.writeAndReleaseAsync(new CacheValue(twinNames.getTags(), twinNames.getReportedProperties())).toCompletableFuture().get();
                } catch (InterruptedException | ExecutionException e) {
                    throw new ExternalDependencyException(String.format("falied to WriteAndRelease lock for %s,%s ", CacheCollectionId, CacheKey));
                }

                if (updated) {
                    return CompletableFuture.supplyAsync(() -> true);
                }

                this.log.warn("Cache rebuilding: write failed due to conflict. Retry soon");
            }
        }
    }

    private boolean needBuild(boolean force, ValueApiModel twin) {
        boolean needBuild = false;
        // validate timestamp
        if (force || twin == null) {
            needBuild = true;
        } else {
            boolean rebuilding = Json.fromJson(Json.parse(twin.getData()), CacheValue.class).isRebuilding();
            DateTimeFormatter formatter = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ssZZ");
            DateTime timestamp = formatter.parseDateTime(twin.getMetadata().get("$modified"));
            needBuild = needBuild || !rebuilding && timestamp.plusSeconds(this.cacheTTL).isBeforeNow();
            needBuild = needBuild || rebuilding && timestamp.plusSeconds(this.rebuildTimeout).isBeforeNow();
        }
        return needBuild;
    }

    private CompletionStage<DeviceTwinName> getValidNamesAsync() {
        DeviceTwinName fullNameWhitelist = new DeviceTwinName(), prefixWhitelist = new DeviceTwinName();
        parseWhitelist(this.cacheWhitelist, fullNameWhitelist, prefixWhitelist);

        DeviceTwinName validNames = new DeviceTwinName(fullNameWhitelist.getTags(), fullNameWhitelist.getReportedProperties());

        if (!prefixWhitelist.getTags().isEmpty() || !prefixWhitelist.getReportedProperties().isEmpty()) {
            DeviceTwinName allNames = null;
            try {
                allNames = this.iotHubClient.getDeviceTwinNamesAsync().toCompletableFuture().get();
            } catch (InterruptedException | ExecutionException | URISyntaxException e) {
                e.printStackTrace();
            }

            validNames.getTags().addAll(allNames.getTags().stream().
                    filter(m -> prefixWhitelist.getTags().stream().anyMatch(m::startsWith)).collect(Collectors.toSet()));

            validNames.getReportedProperties().addAll(allNames.getReportedProperties().stream().
                    filter(m -> prefixWhitelist.getReportedProperties().stream().anyMatch(m::startsWith)).collect(Collectors.toSet()));
        }

        return CompletableFuture.supplyAsync(() -> validNames);
    }

    private static void parseWhitelist(List<String> whitelist, DeviceTwinName fullNameWhitelist, DeviceTwinName prefixWhitelist) {

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
}