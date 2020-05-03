package com.pchudzik.blog.examples.asynccommunication.circuitbreaker;

import com.netflix.hystrix.Hystrix;
import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandProperties;
import com.pchudzik.blog.examples.asynccommunication.Hello;
import com.pchudzik.blog.examples.asynccommunication.circuitbreaker.BreakableService.FixedResponseHandler;
import com.pchudzik.blog.examples.asynccommunication.circuitbreaker.BreakableService.RandomResponseHandler;
import feign.Logger;
import feign.Request;
import feign.gson.GsonDecoder;
import feign.hystrix.HystrixFeign;
import feign.jaxrs.JAXRSContract;
import feign.slf4j.Slf4jLogger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;

public class FeignCircuitBreakerTest {
    private BreakableService breakableService;
    private FixedResponseHandler responseHandler;

    @Before
    public void setup() throws Exception {
        responseHandler = new FixedResponseHandler();
        breakableService = new BreakableService(responseHandler);
        breakableService.startServer();
    }

    @After
    public void tearDown() {
        breakableService.stopServer();
        Hystrix.reset();
    }

    @Test
    public void everything_works_just_fine() {
        HelloWorld helloWorld = HystrixFeign.builder()
                .options(new Request.Options(2, TimeUnit.SECONDS, 2, TimeUnit.SECONDS, true))
                .contract(new JAXRSContract())
                .logger(new Slf4jLogger())
                .logLevel(Logger.Level.BASIC)
                .decoder(new GsonDecoder())
                .target(HelloWorld.class, "http://localhost:" + breakableService.getPort());

        assertThat(helloWorld.sayHello().execute().getMessage(), equalTo(responseHandler.message));
    }

    @Test
    public void circuit_breaker_opens_and_closes() {
        String fallbackValue = "fallback value";
        HelloWorld helloWorld = HystrixFeign.builder()
                .options(new Request.Options(2, TimeUnit.SECONDS, 2, TimeUnit.SECONDS, true))
                .contract(new JAXRSContract())
                .logger(new Slf4jLogger())
                .logLevel(Logger.Level.BASIC)
                .decoder(new GsonDecoder())
                .setterFactory((target, method) ->
                        HystrixCommand.Setter
                                .withGroupKey(HystrixCommandGroupKey.Factory.asKey(target.name()))
                                .andCommandPropertiesDefaults(HystrixCommandProperties.Setter()
                                        .withCircuitBreakerSleepWindowInMilliseconds(10)
                                        .withCircuitBreakerRequestVolumeThreshold(10))
                )
                .target(HelloWorld.class, "http://localhost:" + breakableService.getPort(), () -> new HystrixCommand<Hello>(HystrixCommandGroupKey.Factory.asKey("default")) {
                    @Override
                    protected Hello run() throws Exception {
                        return new Hello(fallbackValue);
                    }
                });

        breakableService.useResponseHandler(new FixedResponseHandler(500, "error"));
        boolean isCircuitBreakerOpen = false;
        while (!isCircuitBreakerOpen) {
            HystrixCommand<Hello> cmd = helloWorld.sayHello();
            isCircuitBreakerOpen = cmd.isCircuitBreakerOpen();
            cmd.execute();
        }

        assertThat(helloWorld.sayHello().execute().getMessage(), equalTo(fallbackValue));
        assertThat(isCircuitBreakerOpen, equalTo(true));

        String workingAgainMessage = "working again";
        breakableService.useResponseHandler(new FixedResponseHandler(200, workingAgainMessage));
        while (isCircuitBreakerOpen) {
            HystrixCommand<Hello> cmd = helloWorld.sayHello();
            isCircuitBreakerOpen = cmd.isCircuitBreakerOpen();
            cmd.execute();
        }
        assertThat(isCircuitBreakerOpen, equalTo(false));
        assertThat(helloWorld.sayHello().execute().getMessage(), equalTo(workingAgainMessage));
    }

    @Test
    public void circuit_breaker_doesnot_open_unless_error_threshold_satisfied() {
        String fallbackValue = "fallback value";
        double errorRate = .60;
        HelloWorld helloWorld = HystrixFeign.builder()
                .options(new Request.Options(2, TimeUnit.SECONDS, 2, TimeUnit.SECONDS, true))
                .contract(new JAXRSContract())
                .logger(new Slf4jLogger())
                .logLevel(Logger.Level.NONE)
                .decoder(new GsonDecoder())
                .setterFactory((target, method) ->
                        HystrixCommand.Setter
                                .withGroupKey(HystrixCommandGroupKey.Factory.asKey(target.name()))
                                .andCommandPropertiesDefaults(HystrixCommandProperties.Setter()
                                        .withCircuitBreakerRequestVolumeThreshold(10)
                                        .withCircuitBreakerSleepWindowInMilliseconds(10)
                                        .withCircuitBreakerErrorThresholdPercentage(80)
                                        .withRequestCacheEnabled(false))
                )
                .target(HelloWorld.class, "http://localhost:" + breakableService.getPort(), () -> new HystrixCommand<Hello>(HystrixCommandGroupKey.Factory.asKey("default")) {
                    @Override
                    protected Hello run() throws Exception {
                        return new Hello(fallbackValue);
                    }
                });

        breakableService.useResponseHandler(new RandomResponseHandler(
                errorRate,
                new FixedResponseHandler(200, "OK"),
                new FixedResponseHandler(500, "ERROR")
        ));

        int requestsToExecute = 5_000;
        for (int i = 0; i < requestsToExecute; i++) {
            HystrixCommand<Hello> cmd = helloWorld.sayHello();
            cmd.execute();
            assertFalse("Circuit breaker should not open", cmd.isCircuitBreakerOpen());
        }
    }

    @Test
    public void circuit_breaker_opens_after_error_threshold_satisfied() {
        String fallbackValue = "fallback value";
        double errorRate = .60;
        HelloWorld helloWorld = HystrixFeign.builder()
                .options(new Request.Options(2, TimeUnit.SECONDS, 2, TimeUnit.SECONDS, true))
                .contract(new JAXRSContract())
                .logger(new Slf4jLogger())
                .logLevel(Logger.Level.NONE)
                .decoder(new GsonDecoder())
                .setterFactory((target, method) ->
                        HystrixCommand.Setter
                                .withGroupKey(HystrixCommandGroupKey.Factory.asKey(target.name()))
                                .andCommandPropertiesDefaults(HystrixCommandProperties.Setter()
                                        .withCircuitBreakerRequestVolumeThreshold(10)
                                        .withCircuitBreakerErrorThresholdPercentage(40)
                                        .withRequestCacheEnabled(false))
                )
                .target(HelloWorld.class, "http://localhost:" + breakableService.getPort(), () -> new HystrixCommand<Hello>(HystrixCommandGroupKey.Factory.asKey("default")) {
                    @Override
                    protected Hello run() throws Exception {
                        return new Hello(fallbackValue);
                    }
                });

        breakableService.useResponseHandler(new RandomResponseHandler(
                errorRate,
                new FixedResponseHandler(200, "OK"),
                new FixedResponseHandler(500, "ERROR")
        ));

        boolean isOpen = false;
        while (!isOpen) {
            HystrixCommand<Hello> cmd = helloWorld.sayHello();
            cmd.execute();
            isOpen = cmd.isCircuitBreakerOpen();
        }
    }

    interface HelloWorld {
        @GET
        @Path("/hello")
        HystrixCommand<Hello> sayHello();
    }
}
