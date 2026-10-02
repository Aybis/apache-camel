package __PACKAGE__;

import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Purges processed_message rows older than processed-message.retention (default 30d), hourly by default. */
@Component
public class __CLASS__HousekeepingRoutes extends RouteBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(__CLASS__HousekeepingRoutes.class);

    private final ProcessedMessages processedMessages;

    public __CLASS__HousekeepingRoutes(ProcessedMessages processedMessages) {
        this.processedMessages = processedMessages;
    }

    @Override
    public void configure() {
        from("timer:__SERVICE__-purge?delay=60000&period={{processed-message.purge-period:3600000}}")
                .routeId("__SERVICE__-purge-processed")
                .process(exchange -> exchange.getMessage().setBody(processedMessages.purge()))
                .log(LoggingLevel.INFO, LOG, "Purged ${body} processed message id(s)");
    }
}
