package com.bmp.web.json;

import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.PackagingUnitId;
import com.bmp.shared.id.PriceListId;
import com.bmp.shared.id.ProductId;
import com.bmp.shared.id.RegisterId;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.TypedId;
import com.bmp.shared.id.UserId;
import com.bmp.shared.id.VariantId;
import com.bmp.shared.money.CurrencyCode;
import com.bmp.shared.money.Money;
import com.bmp.shared.quantity.Quantity;
import com.bmp.shared.quantity.UnitCode;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Function;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * JSON shapes of kernel value objects (03-api-standards.md §8): money as {@code {"amount": "1250.00", "currency":
 * "LKR"}} and quantities as {@code {"value": "0.750", "unit": "l"}} with decimal <em>strings</em>, typed ids as plain
 * UUID strings, and plain {@link BigDecimal}s (quantities, factors, amounts in events) as decimal strings, never JSON
 * numbers (02-coding-standards.md §5). Used for HTTP and for events in the publication registry.
 */
public final class BmpJacksonModule extends SimpleModule {

    private static final long serialVersionUID = 1L;

    public BmpJacksonModule() {
        super("bmp-kernel");
        addSerializer(Money.class, new MoneySerializer());
        addDeserializer(Money.class, new MoneyDeserializer());
        addSerializer(Quantity.class, new QuantitySerializer());
        addDeserializer(Quantity.class, new QuantityDeserializer());
        addSerializer(TypedId.class, new TypedIdSerializer());
        typedId(TenantId.class, TenantId::new);
        typedId(UserId.class, UserId::new);
        typedId(DeviceId.class, DeviceId::new);
        typedId(LocationId.class, LocationId::new);
        typedId(RegisterId.class, RegisterId::new);
        typedId(ProductId.class, ProductId::new);
        typedId(VariantId.class, VariantId::new);
        typedId(PackagingUnitId.class, PackagingUnitId::new);
        typedId(PriceListId.class, PriceListId::new);
        addSerializer(BigDecimal.class, new DecimalSerializer());
        addDeserializer(BigDecimal.class, new DecimalDeserializer());
    }

    /** Registers a module-specific typed id so it (de)serializes as a plain UUID string. */
    public <T extends TypedId> BmpJacksonModule typedId(Class<T> type, Function<UUID, T> factory) {
        addDeserializer(type, new TypedIdDeserializer<>(type, factory));
        return this;
    }

    private static final class MoneySerializer extends ValueSerializer<Money> {
        @Override
        public void serialize(Money value, JsonGenerator gen, SerializationContext ctxt) throws JacksonException {
            gen.writeStartObject();
            gen.writeStringProperty("amount", value.amount().toPlainString());
            gen.writeStringProperty("currency", value.currency().code());
            gen.writeEndObject();
        }
    }

    private static final class MoneyDeserializer extends ValueDeserializer<Money> {
        @Override
        public Money deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
            JsonNode node = ctxt.readTree(p);
            BigDecimal amount = decimal(node, "amount", Money.class, ctxt);
            String currency = text(node, "currency", Money.class, ctxt);
            try {
                return new Money(amount, CurrencyCode.of(currency));
            } catch (IllegalArgumentException e) {
                return ctxt.reportInputMismatch(Money.class, "Unknown currency");
            }
        }
    }

    private static final class QuantitySerializer extends ValueSerializer<Quantity> {
        @Override
        public void serialize(Quantity value, JsonGenerator gen, SerializationContext ctxt) throws JacksonException {
            gen.writeStartObject();
            gen.writeStringProperty("value", value.value().toPlainString());
            gen.writeStringProperty("unit", value.unit().code());
            gen.writeEndObject();
        }
    }

    private static final class QuantityDeserializer extends ValueDeserializer<Quantity> {
        @Override
        public Quantity deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
            JsonNode node = ctxt.readTree(p);
            BigDecimal value = decimal(node, "value", Quantity.class, ctxt);
            String unit = text(node, "unit", Quantity.class, ctxt);
            try {
                return new Quantity(value, UnitCode.of(unit));
            } catch (IllegalArgumentException e) {
                return ctxt.reportInputMismatch(Quantity.class, "Malformed unit");
            }
        }
    }

    private static final class DecimalSerializer extends ValueSerializer<BigDecimal> {
        @Override
        public void serialize(BigDecimal value, JsonGenerator gen, SerializationContext ctxt) throws JacksonException {
            gen.writeString(value.toPlainString());
        }
    }

    /** Reads decimal strings, and JSON numbers for leniency towards older payloads. */
    private static final class DecimalDeserializer extends ValueDeserializer<BigDecimal> {
        @Override
        public BigDecimal deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
            if (p.currentToken().isNumeric()) {
                return p.getDecimalValue();
            }
            String raw = p.getValueAsString();
            if (raw == null) {
                return ctxt.reportInputMismatch(BigDecimal.class, "Expected a decimal string");
            }
            try {
                return new BigDecimal(raw.trim());
            } catch (NumberFormatException e) {
                return ctxt.reportInputMismatch(BigDecimal.class, "Expected a decimal string");
            }
        }
    }

    private static final class TypedIdSerializer extends ValueSerializer<TypedId> {
        @Override
        public void serialize(TypedId value, JsonGenerator gen, SerializationContext ctxt) throws JacksonException {
            gen.writeString(value.value().toString());
        }
    }

    private static final class TypedIdDeserializer<T extends TypedId> extends ValueDeserializer<T> {
        private final Class<T> type;
        private final Function<UUID, T> factory;

        TypedIdDeserializer(Class<T> type, Function<UUID, T> factory) {
            this.type = type;
            this.factory = factory;
        }

        @Override
        public T deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
            String raw = p.getValueAsString();
            if (raw == null) {
                return ctxt.reportInputMismatch(type, "Expected a UUID string");
            }
            try {
                return factory.apply(UUID.fromString(raw));
            } catch (IllegalArgumentException e) {
                return ctxt.reportInputMismatch(type, "Expected a UUID string");
            }
        }
    }

    private static BigDecimal decimal(JsonNode node, String field, Class<?> type, DeserializationContext ctxt) {
        String text = text(node, field, type, ctxt);
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            return ctxt.reportInputMismatch(type, "'" + field + "' must be a decimal string");
        }
    }

    private static String text(JsonNode node, String field, Class<?> type, DeserializationContext ctxt) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) {
            return ctxt.reportInputMismatch(type, "'" + field + "' must be a string");
        }
        return value.asString();
    }
}
