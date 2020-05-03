package com.pchudzik.blog.examples.asynccommunication.circuitbreaker;


import com.pchudzik.blog.examples.asynccommunication.Hello;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retrofit.CircuitBreakerCallAdapter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.http.GET;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;

public class Resilience4jRetrofitCircuitBreakerTest {
    BreakableService breakableService;

    @Before
    public void setup() throws Exception {
        breakableService = new BreakableService(new BreakableService.FixedResponseHandler(200, "hello world"));
        breakableService.startServer();
    }

    @After
    public void tearDown() {
        breakableService.stopServer();
    }

    @Test
    public void everything_works() throws Exception {
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("testName");
        Retrofit retrofit = new Retrofit.Builder()
                .addCallAdapterFactory(CircuitBreakerCallAdapter.of(circuitBreaker))
                .addConverterFactory(GsonConverterFactory.create())
                .baseUrl("http://localhost:" + breakableService.getPort() + "/")
                .build();
        HelloWorld helloWorld = retrofit.create(HelloWorld.class);

        assertEquals("hello world", helloWorld.sayHello().execute().body().getMessage());
    }

    @Test(expected = CallNotPermittedException.class)
    public void when_circuit_breaker_open_it_doesnot_accept_requests() throws Exception {
        breakableService.useResponseHandler(new BreakableService.FixedResponseHandler(500, "error"));

        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("testName");
        Retrofit retrofit = new Retrofit.Builder()
                .addCallAdapterFactory(CircuitBreakerCallAdapter.of(circuitBreaker))
                .addConverterFactory(GsonConverterFactory.create())
                .baseUrl("http://localhost:" + breakableService.getPort() + "/")
                .build();
        HelloWorld helloWorld = retrofit.create(HelloWorld.class);

        circuitBreaker.transitionToOpenState();

        helloWorld.sayHello().execute();
    }

    @Test
    public void circuit_breaker_opens_after_defined_threshold() throws Exception {
        breakableService.useResponseHandler(new BreakableService.FixedResponseHandler(500, "error"));

        int slidingWindowSize = 30;
        CircuitBreaker circuitBreaker = CircuitBreaker.of(
                "testName",
                CircuitBreakerConfig.custom()
                        .slidingWindowSize(slidingWindowSize)
                        .build());
        Retrofit retrofit = new Retrofit.Builder()
                .addCallAdapterFactory(CircuitBreakerCallAdapter.of(circuitBreaker))
                .addConverterFactory(GsonConverterFactory.create())
                .baseUrl("http://localhost:" + breakableService.getPort() + "/")
                .build();
        HelloWorld helloWorld = retrofit.create(HelloWorld.class);

        int requestsCount = executeUntil(
                () -> {
                    Response<Hello> response = helloWorld.sayHello().execute();
                    assertEquals(500, response.code());
                    assertEquals(BreakableService.message("error"), response.errorBody().string());
                },
                () -> circuitBreaker.getState() != CircuitBreaker.State.OPEN);

        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
        assertEquals(slidingWindowSize, requestsCount);
    }

    @Test
    public void circuit_breaker_opens_and_closes() throws Exception {
        int slidingWindowSize = 30;
        int halfOpenCalls = 10;
        CircuitBreaker circuitBreaker = CircuitBreaker.of(
                "testName",
                CircuitBreakerConfig.custom()
                        .slidingWindowSize(slidingWindowSize)
                        .permittedNumberOfCallsInHalfOpenState(halfOpenCalls)
                        .automaticTransitionFromOpenToHalfOpenEnabled(true)
                        .waitDurationInOpenState(Duration.of(100, ChronoUnit.MILLIS))
                        .build());
        Retrofit retrofit = new Retrofit.Builder()
                .addCallAdapterFactory(CircuitBreakerCallAdapter.of(circuitBreaker))
                .addConverterFactory(GsonConverterFactory.create())
                .baseUrl("http://localhost:" + breakableService.getPort() + "/")
                .build();
        HelloWorld helloWorld = retrofit.create(HelloWorld.class);

        breakableService.useResponseHandler(new BreakableService.FixedResponseHandler(500, "error"));

        int failedRequestsCount = executeUntil(
                () -> helloWorld.sayHello().execute(),
                () -> circuitBreaker.getState() != CircuitBreaker.State.OPEN);

        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
        assertEquals(slidingWindowSize, failedRequestsCount);

        executeUntil(
                () -> Thread.sleep(10L),
                () -> circuitBreaker.getState() == CircuitBreaker.State.OPEN);

        breakableService.useResponseHandler(new BreakableService.FixedResponseHandler(200, "back to normal"));
        int halfOpenRequestCount = executeUntil(
                () -> helloWorld.sayHello().execute(),
                () -> circuitBreaker.getState() != CircuitBreaker.State.CLOSED);

        assertEquals(halfOpenCalls, halfOpenRequestCount);
    }

    @Test
    public void circuit_breaker_opens_after_error_threshold_reached() throws Exception {
        int slidingWindowSize = 30;
        int halfOpenCalls = 10;
        CircuitBreaker circuitBreaker = CircuitBreaker.of(
                "testName",
                CircuitBreakerConfig.custom()
                        .slidingWindowSize(slidingWindowSize)
                        .permittedNumberOfCallsInHalfOpenState(halfOpenCalls)
                        .automaticTransitionFromOpenToHalfOpenEnabled(true)
                        .failureRateThreshold(.5f)
                        .build());
        Retrofit retrofit = new Retrofit.Builder()
                .addCallAdapterFactory(CircuitBreakerCallAdapter.of(circuitBreaker))
                .addConverterFactory(GsonConverterFactory.create())
                .baseUrl("http://localhost:" + breakableService.getPort() + "/")
                .build();
        HelloWorld helloWorld = retrofit.create(HelloWorld.class);

        breakableService.useResponseHandler(new BreakableService.RandomResponseHandler(
                .6,
                new BreakableService.FixedResponseHandler(200, "working"),
                new BreakableService.FixedResponseHandler(500, "error")));

        executeUntil(
                () -> helloWorld.sayHello().execute(),
                () -> circuitBreaker.getState() != CircuitBreaker.State.OPEN);

        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
    }

    private int executeUntil(Action action, Supplier<Boolean> circuitBreakerState) throws Exception {
        int executedRequests = 0;
        while (circuitBreakerState.get()) {
            action.run();
            executedRequests++;
        }
        return executedRequests;
    }

    interface HelloWorld {
        @GET("hello")
        Call<Hello> sayHello();
    }

    interface Action {
        void run() throws Exception;
    }
}
