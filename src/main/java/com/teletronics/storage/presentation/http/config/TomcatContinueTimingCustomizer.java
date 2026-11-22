package com.teletronics.storage.presentation.http.config;

import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

/**
 * Sends "100 Continue" only when the application first reads the body (Tomcat continueResponseTiming=onRead).
 * Every check that needs no body runs first, so a client sending Expect: 100-continue gets the rejection
 * before it sends any body byte (API-06, API-13, API-17). There is no Spring Boot property for this.
 */
@Component
public class TomcatContinueTimingCustomizer implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    public static final String ON_READ = "onRead";

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        factory.addConnectorCustomizers(connector -> {
            if (connector.getProtocolHandler() instanceof AbstractHttp11Protocol<?> protocol) {
                protocol.setContinueResponseTiming(ON_READ);
            }
        });
    }
}
