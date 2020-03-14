package com.pchudzik.blog.examples.asynccommunication;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

public class WireMockScenario {
    private final WireMockServer wireMock;
    private final MappingBuilder mapping;

    private WireMockScenario(WireMockServer wireMock, MappingBuilder mapping) {
        this.wireMock = wireMock;
        this.mapping = mapping;
    }

    public static WireMockScenario of(MappingBuilder mapping, WireMockServer wireMock) {
        return new WireMockScenario(wireMock, mapping);
    }

    public void willRespondWith(ResponseDefinitionBuilder... responses) {
        String scenario = "retry example";
        for (int i = 0; i < responses.length; i++) {
            String step = "" + i;
            String nextStep = "" + (i + 1);
            if (i == 0) {
                step = Scenario.STARTED;
            }

            wireMock
                    .stubFor(mapping
                            .inScenario(scenario)
                            .whenScenarioStateIs(step)
                            .willSetStateTo(nextStep)
                            .willReturn(responses[i]));
        }
    }
}
