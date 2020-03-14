package com.pchudzik.blog.examples.asynccommunication.retries;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.pchudzik.blog.examples.asynccommunication.Hello;
import com.pchudzik.blog.examples.asynccommunication.WireMockScenario;
import feign.*;
import feign.codec.ErrorDecoder;
import feign.gson.GsonDecoder;
import feign.gson.GsonEncoder;
import feign.jaxrs.JAXRSContract;
import feign.slf4j.Slf4jLogger;
import org.junit.Rule;
import org.junit.Test;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;

public class RetriesWithFeignTest {
    private static final ResponseDefinitionBuilder OK_RESPONSE = aResponse().withStatus(200).withBody("{\"message\":\"hello world\"}");

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(options().port(8080), true);

    @Test
    public void retries_does_not_work_for_5xx_server_responses_by_default() {
        WireMockScenario
                .of(get(urlMatching("/hello")), wireMockRule)
                .willRespondWith(
                        aResponse().withStatus(503).withBody("Error"),
                        OK_RESPONSE);

        HelloWorld target = Feign.builder()
                .contract(new JAXRSContract())
                .logger(new Slf4jLogger())
                .logLevel(Logger.Level.BASIC)
                .decoder(new GsonDecoder())
                .encoder(new GsonEncoder())
                .options(new Request.Options(2, TimeUnit.SECONDS, 2, TimeUnit.SECONDS, true))
                .retryer(new Retryer.Default(50, 200, 2))
                .target(HelloWorld.class, "http://localhost:8080");

        try {
            target.sayHello();
            fail("Should throw exception");
        } catch (FeignException.ServiceUnavailable ex) {
            assertThat(
                    ex.getMessage().toLowerCase(),
                    containsString("503 service unavailable"));
        }
    }

    @Test
    public void feign_retries_sample() {
        WireMockScenario
                .of(get(urlMatching("/hello")), wireMockRule)
                .willRespondWith(
                        aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER),
                        aResponse().withStatus(503).withBody("service unavailable"),
                        OK_RESPONSE);

        HelloWorld target = Feign.builder()
                .options(new Request.Options(2, TimeUnit.SECONDS, 2, TimeUnit.SECONDS, true))
                .retryer(new Retryer.Default(50, 200, 3))

                .contract(new JAXRSContract())
                .logger(new Slf4jLogger())
                .logLevel(Logger.Level.BASIC)
                .decoder(new GsonDecoder())
                .encoder(new GsonEncoder())
                .errorDecoder(new ErrorDecoder.Default() {
                    @Override
                    public Exception decode(String methodKey, Response response) {
                        if (response.status() == 503) {
                            return new RetryableException(
                                    response.status(), "Received " + response.status() + " from server",
                                    response.request().httpMethod(), null, response.request());
                        }

                        return super.decode(methodKey, response);
                    }
                })
                .target(HelloWorld.class, "http://localhost:8080");

        String value = target.sayHello().getMessage();

        assertThat(value, equalTo("hello world"));
    }

    interface HelloWorld {
        @GET
        @Path("/hello")
        Hello sayHello();
    }
}
