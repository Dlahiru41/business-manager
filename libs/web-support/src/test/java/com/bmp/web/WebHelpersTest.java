package com.bmp.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.shared.context.ActorContext;
import com.bmp.shared.context.ContextBinding;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import com.bmp.shared.id.VariantId;
import com.bmp.shared.money.CurrencyCode;
import com.bmp.shared.money.Money;
import com.bmp.shared.quantity.Quantity;
import com.bmp.shared.quantity.UnitCode;
import com.bmp.web.etag.Etags;
import com.bmp.web.idempotency.RequestHasher;
import com.bmp.web.json.BmpJacksonModule;
import com.bmp.web.pagination.CursorCodec;
import com.bmp.web.pagination.PageQuery;
import com.bmp.web.pagination.PageResponse;
import com.bmp.web.problem.ConstraintErrorMapping;
import com.bmp.web.problem.ConstraintErrorRegistry;
import com.bmp.web.request.MdcTaskDecorator;
import com.bmp.web.testapp.TestErrorCode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

class WebHelpersTest {

    private static final JsonMapper JSON =
            JsonMapper.builder().addModule(new BmpJacksonModule()).build();

    @Test
    void etagsShouldRoundTripVersions() {
        assertThat(Etags.of(7)).isEqualTo("W/\"7\"");
        assertThat(Etags.parse("W/\"7\"")).hasValue(7);
        assertThat(Etags.parse(" \"8\" ")).hasValue(8);
        assertThat(Etags.parse("*")).isEmpty();
        assertThat(Etags.parse(null)).isEmpty();
        assertThat(Etags.parse("W/\"abc\"")).isEmpty();
    }

    @Test
    void cursorCodecShouldRoundTripAndRejectTampering() {
        String cursor = CursorCodec.encode(List.of("2026-11-21T09:42:13Z", "0193a6f1-0000-7000-8000-000000000001"));

        assertThat(CursorCodec.decode(cursor, 2))
                .containsExactly("2026-11-21T09:42:13Z", "0193a6f1-0000-7000-8000-000000000001");
        assertThatThrownBy(() -> CursorCodec.decode(cursor, 3)).isInstanceOf(BusinessRuleViolation.class);
        assertThatThrownBy(() -> CursorCodec.decode("%%%", 1)).isInstanceOf(BusinessRuleViolation.class);
        assertThatIllegalArgumentException().isThrownBy(() -> CursorCodec.encode(List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> CursorCodec.encode(List.of("a\u001Fb")));
    }

    @Test
    void pageQueryShouldApplyDefaultsAndLimits() {
        assertThat(PageQuery.of(null, null).limit()).isEqualTo(50);
        assertThat(PageQuery.of(200, " ").cursor()).isNull();
        assertThat(PageQuery.of(10, null).fetchSize()).isEqualTo(11);
        assertThatThrownBy(() -> PageQuery.of(0, null)).isInstanceOf(BusinessRuleViolation.class);
        assertThatThrownBy(() -> PageQuery.of(201, null)).isInstanceOf(BusinessRuleViolation.class);
    }

    @Test
    void pageResponseShouldDropLookAheadRowAndBuildCursor() {
        PageQuery query = PageQuery.of(2, null);

        PageResponse<String> more = PageResponse.of(List.of("a", "b", "c"), query, List::of);
        PageResponse<String> last = PageResponse.of(List.of("a"), query, List::of);

        assertThat(more.items()).containsExactly("a", "b");
        assertThat(more.page().hasMore()).isTrue();
        assertThat(CursorCodec.decode(more.page().nextCursor(), 1)).containsExactly("b");
        assertThat(last.page().hasMore()).isFalse();
        assertThat(last.page().nextCursor()).isNull();
        assertThat(more.map(String::toUpperCase).items()).containsExactly("A", "B");
    }

    @Test
    void requestHasherShouldIgnorePropertyOrderAndWhitespace() {
        RequestHasher hasher = new RequestHasher(JSON);
        byte[] a = "{\"b\":1,\"a\":{\"y\":2,\"x\":1}}".getBytes(StandardCharsets.UTF_8);
        byte[] b = "{ \"a\": {\"x\":1, \"y\":2}, \"b\": 1 }".getBytes(StandardCharsets.UTF_8);
        byte[] c = "{\"a\":{\"x\":1,\"y\":3},\"b\":1}".getBytes(StandardCharsets.UTF_8);

        assertThat(hasher.hash("POST", "/x", a)).isEqualTo(hasher.hash("POST", "/x", b));
        assertThat(hasher.hash("POST", "/x", a)).isNotEqualTo(hasher.hash("POST", "/x", c));
        assertThat(hasher.hash("POST", "/x", a)).isNotEqualTo(hasher.hash("POST", "/y", a));
        assertThat(hasher.hash("POST", "/x", new byte[0])).hasSize(64);
        assertThat(hasher.hash("POST", "/x", "not json".getBytes(StandardCharsets.UTF_8)))
                .hasSize(64);
    }

    @Test
    void jacksonModuleShouldUseDecimalStringsAndPlainUuids() {
        Money money = Money.of("1250.00", CurrencyCode.LKR);
        Quantity quantity = Quantity.of("0.750", UnitCode.of("l"));
        TenantId tenant = TenantId.of(UUID.fromString("0193a6f1-0000-7000-8000-000000000001"));

        assertThat(JSON.writeValueAsString(money)).isEqualTo("{\"amount\":\"1250.00\",\"currency\":\"LKR\"}");
        assertThat(JSON.writeValueAsString(quantity)).isEqualTo("{\"value\":\"0.750\",\"unit\":\"l\"}");
        assertThat(JSON.writeValueAsString(tenant)).isEqualTo("\"0193a6f1-0000-7000-8000-000000000001\"");
        assertThat(JSON.readValue(JSON.writeValueAsString(money), Money.class)).isEqualTo(money);
        assertThat(JSON.readValue(JSON.writeValueAsString(quantity), Quantity.class))
                .isEqualTo(quantity);
        assertThat(JSON.readValue("\"0193a6f1-0000-7000-8000-000000000001\"", TenantId.class))
                .isEqualTo(tenant);
        assertThat(JSON.readValue("\"0193a6f1-0000-7000-8000-000000000001\"", UserId.class)
                        .value())
                .isEqualTo(tenant.value());
    }

    @Test
    void jacksonModuleShouldWritePlainDecimalsAsStringsAndReadBothForms() {
        record Line(BigDecimal qtyInBase, VariantId variantId) {}
        Line line = new Line(
                new BigDecimal("1.9565E+4"), VariantId.of(UUID.fromString("0193b000-0000-7000-8000-000000000211")));

        String json = JSON.writeValueAsString(line);

        assertThat(json).isEqualTo("{\"qtyInBase\":\"19565\",\"variantId\":\"0193b000-0000-7000-8000-000000000211\"}");
        assertThat(JSON.readValue(json, Line.class)).isEqualTo(line);
        assertThat(JSON.readValue(
                                "{\"qtyInBase\":187.5,\"variantId\":\"0193b000-0000-7000-8000-000000000211\"}",
                                Line.class)
                        .qtyInBase())
                .isEqualByComparingTo("187.5");
        assertThatThrownBy(() -> JSON.readValue("\"12,5\"", BigDecimal.class)).isInstanceOf(JacksonException.class);
    }

    @Test
    void jacksonModuleShouldRejectMalformedValues() {
        assertThatThrownBy(() -> JSON.readValue("{\"amount\":1.5,\"currency\":\"LKR\"}", Money.class))
                .isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> JSON.readValue("{\"amount\":\"x\",\"currency\":\"LKR\"}", Money.class))
                .isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> JSON.readValue("{\"amount\":\"1\",\"currency\":\"ABC\"}", Money.class))
                .isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> JSON.readValue("{\"value\":\"1\",\"unit\":\"1kg\"}", Quantity.class))
                .isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> JSON.readValue("\"nope\"", TenantId.class)).isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> JSON.readValue("42", TenantId.class)).isInstanceOf(JacksonException.class);
    }

    @Test
    void constraintRegistryShouldResolveRegisteredConstraintsOnly() {
        var registry = new ConstraintErrorRegistry(
                List.of(ConstraintErrorMapping.of("uk_thing_tenant_name", TestErrorCode.THING_NAME_TAKEN)));
        var known = new DataIntegrityViolationException(
                "x", new SQLException("duplicate key value violates unique constraint \"uk_thing_tenant_name\""));
        var unknown = new DataIntegrityViolationException("x", new SQLException("violates constraint \"uk_other\""));

        assertThat(registry.resolve(known)).contains(TestErrorCode.THING_NAME_TAKEN);
        assertThat(registry.resolve(unknown)).isEmpty();
        assertThat(registry.resolve(new IllegalStateException())).isEmpty();
        assertThatThrownBy(() -> new ConstraintErrorRegistry(List.of(
                        ConstraintErrorMapping.of("uk_a", TestErrorCode.THING_NAME_TAKEN),
                        ConstraintErrorMapping.of("uk_a", TestErrorCode.THING_LOCKED))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void mdcTaskDecoratorShouldPropagateAndClearContext() throws InterruptedException {
        TenantId tenant = TenantId.of(UUID.randomUUID());
        AtomicReference<String> seen = new AtomicReference<>();
        Runnable decorated;
        MDC.put("requestId", "r-1");
        try (ContextBinding _ = TenantContext.bind(tenant);
                ContextBinding _ = ActorContext.bind(com.bmp.shared.context.Actor.user(UserId.of(UUID.randomUUID())))) {
            decorated = new MdcTaskDecorator()
                    .decorate(() -> seen.set(MDC.get("requestId") + "|" + TenantContext.require() + "|"
                            + ActorContext.current().isPresent()));
        } finally {
            MDC.clear();
        }

        Thread worker = Thread.ofVirtual().start(decorated);
        worker.join();

        assertThat(seen.get()).isEqualTo("r-1|" + tenant + "|true");
    }
}
