// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.azure.iotsolutions.uiconfig.services;

import com.microsoft.azure.iotsolutions.uiconfig.services.exceptions.BaseException;
import com.microsoft.azure.iotsolutions.uiconfig.services.external.*;
import com.microsoft.azure.iotsolutions.uiconfig.services.models.CacheValue;
import com.microsoft.azure.iotsolutions.uiconfig.services.models.DeviceTwinName;
import com.microsoft.azure.iotsolutions.uiconfig.services.runtime.IServicesConfig;
import com.microsoft.azure.iotsolutions.uiconfig.services.runtime.ServicesConfig;
import helpers.Random;
import helpers.UnitTest;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mockito;
import play.libs.Json;

import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertEquals;

public class CacheTest {
    private IStorageAdapterClient mockStorageAdapterClient;
    private IIothubManagerServiceClient mockIothubManagerClient;
    private ISimulationServiceClient mockSimulationClient;
    private String cacheModel = "";
    private IServicesConfig config;
    private Cache cache;
    private Hashtable<String, String> metaData = null;
    private Random rand;

    @Before
    public void setUp() {
        rand = new Random();
        metaData = new Hashtable<>();
        metaData.put("$modified", DateTime.now().plusDays(2).toString("yyyy-MM-dd'T'HH:mm:ssZZ"));
        mockStorageAdapterClient = Mockito.mock(IStorageAdapterClient.class);
        mockIothubManagerClient = Mockito.mock(IIothubManagerServiceClient.class);
        mockSimulationClient = Mockito.mock(ISimulationServiceClient.class);
        cacheModel = "{\"Rebuilding\": false,\"Tags\": [ \"c\", \"a\", \"y\", \"z\" ],\"Reported\": [\"1\",\"9\",\"2\",\"3\"] }";
        config = new ServicesConfig(null, null, null, null, 0, 0, null, null, null);
    }

    @Test(timeout = 100000)
    @Category({UnitTest.class})
    public void getCacheAsyncTestAsync() throws BaseException, ExecutionException, InterruptedException {
        Mockito.when(mockStorageAdapterClient.getAsync(Mockito.any(String.class), Mockito.any(String.class)))
                .thenReturn(CompletableFuture.supplyAsync(() -> new ValueApiModel("", this.cacheModel, "", metaData)));
        cache = new Cache(mockStorageAdapterClient, mockIothubManagerClient, mockSimulationClient, config);
        CacheValue result = this.cache.getCacheAsync().toCompletableFuture().get();
        assertEquals(String.join(",", new TreeSet<String>(result.getTags())), "a,c,y,z");
        assertEquals(String.join(",", new TreeSet<String>(result.getReported())), "1,2,3,9");
    }

    @Test(timeout = 100000)
    @Category({UnitTest.class})
    public void setCacheAsyncTestAsync() throws BaseException, ExecutionException, InterruptedException {
        Mockito.when(mockStorageAdapterClient.getAsync(Mockito.any(String.class), Mockito.any(String.class)))
                .thenReturn(CompletableFuture.supplyAsync(() -> new ValueApiModel("", this.cacheModel, "", metaData)));
        CacheValue resultModel = new CacheValue(new HashSet<String>(Arrays.asList("c", "a", "y", "z", "@", "#")),
                new HashSet<>(Arrays.asList("1", "9", "2", "3", "12", "11")), false);
        CacheValue model = new CacheValue(new HashSet<String>(Arrays.asList("a", "y", "z", "@", "#")),
                new HashSet<>(Arrays.asList("9", "2", "3", "11", "12")), false);
        Mockito.when(mockStorageAdapterClient.updateAsync(Mockito.any(String.class), Mockito.any(String.class),
                Mockito.any(String.class), Mockito.any(String.class)))
                .thenReturn(CompletableFuture.supplyAsync(() -> new ValueApiModel("", Json.stringify(Json.toJson(resultModel)), "", null)));
        cache = new Cache(mockStorageAdapterClient, mockIothubManagerClient, mockSimulationClient, config);
        CacheValue result = this.cache.setCacheAsync(model).toCompletableFuture().get();
        assertEquals(String.join(",", new TreeSet<String>(result.getTags())), "#,@,a,c,y,z");
        assertEquals(String.join(",", new TreeSet<String>(result.getReported())), "1,11,12,2,3,9");
    }

    @Test(timeout = 100000)
    @Category({UnitTest.class})
    public void rebuildCacheTestAsync() throws BaseException, URISyntaxException, ExecutionException, InterruptedException {
        String etagOld = this.rand.NextString();
        String etagLock = this.rand.NextString();
        String etagNew = this.rand.NextString();
        Mockito.when(mockStorageAdapterClient.getAsync("cache", "twin"))
                .thenReturn(CompletableFuture.supplyAsync(() -> new ValueApiModel("", "{\"Rebuilding\": false}", etagOld, metaData)));
        Mockito.when(mockIothubManagerClient.getDeviceTwinNamesAsync())
                .thenReturn(CompletableFuture.supplyAsync(() -> new DeviceTwinName(new HashSet<>(Arrays.asList("Building", "Group")), new HashSet<>(Arrays.asList("Config.Interval", "otherProperty")))));
        Mockito.when(mockSimulationClient.getDevicePropertyNamesAsync())
                .thenReturn(CompletableFuture.supplyAsync(() -> new HashSet<>(Arrays.asList("MethodStatus", "UpdateStatus"))));
        Mockito.when(mockStorageAdapterClient.updateAsync(Mockito.eq("cache"), Mockito.eq("twin"), Mockito.anyString(), Mockito.eq(etagOld)))
                .thenReturn(CompletableFuture.supplyAsync(() -> {
                    ValueApiModel result = new ValueApiModel();
                    result.setETag(etagLock);
                    return result;
                }));
        Mockito.when(mockStorageAdapterClient.updateAsync(Mockito.eq("cache"), Mockito.eq("twin"), Mockito.anyString(), Mockito.eq(etagLock)))
                .thenReturn(CompletableFuture.supplyAsync(() -> {
                    ValueApiModel result = new ValueApiModel();
                    result.setETag(etagNew);
                    return result;
                }));
        ServicesConfig config = Mockito.mock(ServicesConfig.class);
        Mockito.when(config.getCacheTTL()).thenReturn(3600);
        Mockito.when(config.getCacheWhiteList()).thenReturn(Arrays.asList("tags.*", " reported.Type", " reported.Config.*"));
        cache = new Cache(
                mockStorageAdapterClient,
                mockIothubManagerClient,
                mockSimulationClient,
                config);
        assertEquals(true, cache.rebuildCacheAsync(true).toCompletableFuture().get());
        Mockito.verify(mockStorageAdapterClient).getAsync("cache", "twin");
    }

    private static boolean rebuilding(String data) {
        return Json.fromJson(Json.parse(data), CacheValue.class).isRebuilding();
    }

}
