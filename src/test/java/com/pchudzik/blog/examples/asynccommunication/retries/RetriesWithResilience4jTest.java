package com.pchudzik.blog.examples.asynccommunication.retries;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.pchudzik.blog.examples.asynccommunication.Hello;
import com.pchudzik.blog.examples.asynccommunication.WireMockScenario;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import org.junit.Rule;
import org.junit.Test;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.http.GET;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

public class RetriesWithResilience4jTest {
    private static final ResponseDefinitionBuilder OK_RESPONSE = aResponse().withStatus(200).withBody("{\"message\":\"hello world\"}");

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(options().port(8080), true);

    @Test
    public void resilience4j_retries_sample() throws Throwable {
        WireMockScenario
                .of(get(urlMatching("/hello")), wireMockRule)
                .willRespondWith(
                        aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER),
                        aResponse().withStatus(503).withBody("Error"),
                        OK_RESPONSE);

        HelloWorld target = new Retrofit.Builder()
                .client(new OkHttpClient.Builder()
                        .connectTimeout(2, TimeUnit.SECONDS)
                        .readTimeout(2, TimeUnit.SECONDS)
                        .callTimeout(3, TimeUnit.SECONDS)
                        .addInterceptor(new HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
                        .build())
                .baseUrl("http://localhost:8080/")
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(HelloWorld.class);

        RetryRegistry simpleRetryRegistry = RetryRegistry.of(RetryConfig.<Response<Hello>>custom()
                .intervalFunction(IntervalFunction.ofExponentialBackoff(50, 1.5))
                .maxAttempts(3)
                .retryOnException(ex -> {
                    System.out.println(ex.getMessage());
                    return true;
                })
                .retryOnResult(response -> response.code() == 503)
                .build());

        Optional<String> value = Optional
                .ofNullable(simpleRetryRegistry
                        .retry("hello world")
                        .executeCallable(() -> target.sayHello().execute())
                        .body())
                .map(Hello::getMessage);

        assertThat(value.orElse("FAIL"), equalTo("hello world"));
    }

    interface HelloWorld {
        @GET("hello")
        Call<Hello> sayHello();
    }

}
