package com.amanahconnect.common.money;

import java.math.BigDecimal;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * Money is a string in JSON. Every {@link BigDecimal} in this application is an amount, so all of
 * them are written as plain strings ({@code "1250.50"}), never as JSON numbers (which clients parse as
 * floating point) and never in scientific notation ({@code 1E+3}). {@link Money} does the same through
 * its own {@code @JsonValue}.
 */
@Configuration
public class MoneyJacksonConfig {

    @Bean
    public SimpleModule moneyAsStringModule() {
        SimpleModule module = new SimpleModule("amanah-money");
        module.addSerializer(BigDecimal.class, new PlainStringSerializer());
        return module;
    }

    static final class PlainStringSerializer extends StdSerializer<BigDecimal> {

        PlainStringSerializer() {
            super(BigDecimal.class);
        }

        @Override
        public void serialize(BigDecimal value, JsonGenerator generator, SerializationContext context) {
            generator.writeString(value.toPlainString());
        }
    }
}
