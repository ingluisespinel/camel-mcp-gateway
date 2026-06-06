package com.lespinel.camel.mcp.routes.processors;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.stereotype.Component;

@Component
public class HttpResponseValidator implements Processor {

    @Override
    public void process(Exchange exchange) {
        String body = exchange.getIn().getBody(String.class);
        int code = exchange.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE, 200, Integer.class);
        if (code >= 400) {
            throw new RuntimeException("Backend returned HTTP " + code + ": " + body);
        }
    }
}
