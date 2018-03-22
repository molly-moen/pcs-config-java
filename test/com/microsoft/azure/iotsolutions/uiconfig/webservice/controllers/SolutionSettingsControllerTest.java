// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.azure.iotsolutions.uiconfig.webservice.controllers;

import akka.util.ByteString;
import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.azure.iotsolutions.uiconfig.services.IStorage;
import com.microsoft.azure.iotsolutions.uiconfig.services.exceptions.BaseException;
import com.microsoft.azure.iotsolutions.uiconfig.services.models.Logo;
import com.microsoft.azure.iotsolutions.uiconfig.webservice.v1.controllers.SolutionSettingsController;
import helpers.Random;
import helpers.TestUtils;
import helpers.UnitTest;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mockito;
import play.libs.Json;
import play.mvc.Http;
import play.mvc.Result;

import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static junit.framework.TestCase.assertEquals;

public class SolutionSettingsControllerTest {

    private IStorage mockStorage;
    private SolutionSettingsController controller;
    private Random rand;

    @Before
    public void setUp() {
        mockStorage = Mockito.mock(IStorage.class);
        rand = new Random();
    }

    @Test(timeout = 100000)
    @Category({UnitTest.class})
    public void getThemeAsyncTest() throws BaseException, ExecutionException, InterruptedException {
        String name = rand.NextString();
        String description = rand.NextString();
        Object model = Json.fromJson(Json.parse(String.format("{\"Name\":\"%s\",\"Description\":\"%s\"}", name, description)), Object.class);
        Mockito.when(mockStorage.getThemeAsync()).thenReturn(CompletableFuture.supplyAsync(() -> model));
        controller = new SolutionSettingsController(mockStorage);
        String resultStr = TestUtils.getString(controller.getThemeAsync().toCompletableFuture().get());
        JsonNode result = Json.toJson(Json.parse(resultStr));
        assertEquals(result.get("Name").asText(), name);
        assertEquals(result.get("Description").asText(), description);
    }

    @Test(timeout = 100000)
    @Category({UnitTest.class})
    public void setThemeAsyncTest() throws ExecutionException, InterruptedException, BaseException {
        String name = rand.NextString();
        String description = rand.NextString();
        Object model = Json.fromJson(Json.parse(String.format("{\"Name\":\"%s\",\"Description\":\"%s\"}", name, description)), Object.class);
        Mockito.when(mockStorage.setThemeAsync(Mockito.any(Object.class))).thenReturn(CompletableFuture.supplyAsync(() -> model));
        controller = new SolutionSettingsController(mockStorage);
        TestUtils.setRequest(String.format("{\"Name\":\"%s\",\"Description\":\"%s\"}", name, description));
        String resultStr = TestUtils.getString(controller.setThemeAsync().toCompletableFuture().get());
        JsonNode result = Json.toJson(Json.parse(resultStr));
        assertEquals(result.get("Name").asText(), name);
        assertEquals(result.get("Description").asText(), description);
    }

    @Test(timeout = 100000)
    @Category({UnitTest.class})
    public void GetLogoShouldReturnExpectedNameAndType() throws BaseException, ExecutionException, InterruptedException {
        String image = rand.NextString();
        String type = rand.NextString();
        String name = rand.NextString();
        Logo model = new Logo(image, type, name, false);
        Mockito.when(mockStorage.getLogoAsync()).thenReturn(CompletableFuture.supplyAsync(() -> model));
        controller = new SolutionSettingsController(mockStorage);
        Http.Response mockResponse = TestUtils.setRequest("{\"Name\":\"1\"}");
        Result result = controller.getLogoAsync().toCompletableFuture().get();
        byte[] bytes = TestUtils.getBytes(result);
        byte[] bytesold = Base64.getDecoder().decode(model.getImage().getBytes());
        assertEquals(ByteString.fromArray(bytes), ByteString.fromArray(bytesold));
        Mockito.verify(mockResponse).setHeader(Logo.NAME_HEADER, name);
        Mockito.verify(mockResponse).setHeader(Logo.IS_DEFAULT_HEADER, Boolean.toString(false));
    }

    @Test(timeout = 100000)
    @Category({UnitTest.class})
    public void GetLogoShouldReturnDefaultLogo() throws BaseException, ExecutionException, InterruptedException {
        Logo model = Logo.Default;
        Mockito.when(mockStorage.getLogoAsync()).thenReturn(CompletableFuture.supplyAsync(() -> model));
        controller = new SolutionSettingsController(mockStorage);
        Http.Response mockResponse = TestUtils.setRequest("{\"Name\":\"1\"}");
        Result result = controller.getLogoAsync().toCompletableFuture().get();
        byte[] bytes = TestUtils.getBytes(result);
        byte[] bytesold = Base64.getDecoder().decode(model.getImage().getBytes());
        assertEquals(ByteString.fromArray(bytes), ByteString.fromArray(bytesold));
        Mockito.verify(mockResponse).setHeader(Logo.NAME_HEADER, Logo.Default.getName());
        Mockito.verify(mockResponse).setHeader(Logo.IS_DEFAULT_HEADER, Boolean.toString(true));
    }

    @Test(timeout = 100000)
    @Category({UnitTest.class})
    public void SetLogoShouldReturnGivenLogoAndName() throws BaseException, ExecutionException, InterruptedException, UnsupportedEncodingException, URISyntaxException {
        String image = rand.NextString();
        String type = rand.NextString();
        String name = rand.NextString();
        Logo model = new Logo(image, type, name, false);
        Mockito.when(mockStorage.setLogoAsync(Mockito.any(Logo.class))).thenReturn(CompletableFuture.supplyAsync(() -> model));
        controller = new SolutionSettingsController(mockStorage);
        Http.Response mockResponse = TestUtils.setRequest("{\"Name\":\"1\"}");
        byte[] bytes = TestUtils.getBytes(controller.setLogoAsync().toCompletableFuture().get());
        byte[] bytesold = Base64.getDecoder().decode(model.getImage().getBytes());
        assertEquals(ByteString.fromArray(bytes), ByteString.fromArray(bytesold));
        Mockito.verify(mockResponse).setHeader(Logo.NAME_HEADER, name);
        Mockito.verify(mockResponse).setHeader(Logo.IS_DEFAULT_HEADER, Boolean.toString(false));
    }

    @Test(timeout = 100000)
    @Category({UnitTest.class})
    public void SetLogoShouldReturnGivenLogo() throws BaseException, ExecutionException, InterruptedException, UnsupportedEncodingException, URISyntaxException {
        String image = rand.NextString();
        String type = rand.NextString();
        Logo model = new Logo(image, type, null, false);
        Mockito.when(mockStorage.setLogoAsync(Mockito.any(Logo.class))).thenReturn(CompletableFuture.supplyAsync(() -> model));
        controller = new SolutionSettingsController(mockStorage);
        Http.Response mockResponse = TestUtils.setRequest("{\"Name\":\"1\"}");
        byte[] bytes = TestUtils.getBytes(controller.setLogoAsync().toCompletableFuture().get());
        byte[] bytesold = Base64.getDecoder().decode(model.getImage().getBytes());
        assertEquals(ByteString.fromArray(bytes), ByteString.fromArray(bytesold));
        Mockito.verify(mockResponse).setHeader(Logo.IS_DEFAULT_HEADER, Boolean.toString(false));
    }

    @Test(timeout = 100000)
    @Category({UnitTest.class})
    public void SetLogoShouldReturnGivenName() throws BaseException, ExecutionException, InterruptedException, UnsupportedEncodingException, URISyntaxException {
        String name = rand.NextString();
        Logo model = new Logo(null, null, name, false);
        Mockito.when(mockStorage.setLogoAsync(Mockito.any(Logo.class))).thenReturn(CompletableFuture.supplyAsync(() -> model));
        controller = new SolutionSettingsController(mockStorage);
        Http.Response mockResponse = TestUtils.setRequest("{\"Name\":\"1\"}");
        byte[] bytes = TestUtils.getBytes(controller.setLogoAsync().toCompletableFuture().get());
        byte[] emptyBytes = new byte[0];
        assertEquals(ByteString.fromArray(bytes), ByteString.fromArray(emptyBytes));
        Mockito.verify(mockResponse).setHeader(Logo.NAME_HEADER, name);
        Mockito.verify(mockResponse).setHeader(Logo.IS_DEFAULT_HEADER, Boolean.toString(false));
    }
}
