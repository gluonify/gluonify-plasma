package io.gluonify.plasma.crystallon.client;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The bounded body subscriber, driven the way the HTTP client drives it: subscription, chunks, completion and errors, in the orders a real stream can produce. */
class BoundedBodySubscriberTest {

    static HttpResponse.ResponseInfo info(final int status, final Map<String, List<String>> headers) {
        return new HttpResponse.ResponseInfo() {
            @Override
            public int statusCode() {
                return status;
            }

            @Override
            public HttpHeaders headers() {
                return HttpHeaders.of(headers, (a, b) -> true);
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }

    static final class Subscription implements Flow.Subscription {
        final AtomicLong requested = new AtomicLong();
        final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public void request(final long n) {
            requested.addAndGet(n);
        }

        @Override
        public void cancel() {
            cancelled.set(true);
        }
    }

    static List<ByteBuffer> chunk(final String text) {
        return List.of(ByteBuffer.wrap(text.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    }

    static HttpResponse.BodySubscriber<BoundedBody.Body> subscriber(final long limit) {
        return BoundedBody.handler(limit).apply(info(200, Map.of()));
    }

    @Test
    void chunksUnderTheLimitAreCollectedInOrder() {
        final HttpResponse.BodySubscriber<BoundedBody.Body> s = subscriber(10);
        final Subscription sub = new Subscription();
        s.onSubscribe(sub);
        assertEquals(Long.MAX_VALUE, sub.requested.get());
        s.onNext(chunk("abc"));
        s.onNext(chunk("def"));
        s.onComplete();
        final BoundedBody.Body body = s.getBody().toCompletableFuture().join();
        assertArrayEquals("abcdef".getBytes(java.nio.charset.StandardCharsets.US_ASCII), body.bytes());
        assertFalse(body.tooLarge());
    }

    @Test
    void aSecondSubscriptionIsCancelledAndTheFirstKeptSingle() {
        final HttpResponse.BodySubscriber<BoundedBody.Body> s = subscriber(10);
        final Subscription first = new Subscription();
        final Subscription second = new Subscription();
        s.onSubscribe(first);
        s.onSubscribe(second);
        assertTrue(second.cancelled.get());
        assertFalse(first.cancelled.get());
        assertEquals(0, second.requested.get());
    }

    @Test
    void crossingTheLimitCancelsTheStreamAndIgnoresWhatFollows() {
        final HttpResponse.BodySubscriber<BoundedBody.Body> s = subscriber(5);
        final Subscription sub = new Subscription();
        s.onSubscribe(sub);
        s.onNext(chunk("abc"));
        s.onNext(chunk("defg"));
        assertTrue(sub.cancelled.get());
        s.onNext(chunk("ignored: already done"));
        s.onComplete();
        s.onError(new IllegalStateException("late"));
        final BoundedBody.Body body = s.getBody().toCompletableFuture().join();
        assertTrue(body.tooLarge());
        assertEquals(0, body.bytes().length);
    }

    @Test
    void aTransportErrorFailsTheBodyOnceAndALaterCompletionChangesNothing() {
        final HttpResponse.BodySubscriber<BoundedBody.Body> s = subscriber(10);
        s.onSubscribe(new Subscription());
        s.onNext(chunk("abc"));
        s.onError(new java.io.IOException("connection reset"));
        s.onError(new java.io.IOException("second error"));
        s.onComplete();
        final CompletionException e = assertThrows(CompletionException.class, () -> s.getBody().toCompletableFuture().join());
        assertEquals("connection reset", e.getCause().getMessage());
    }

    @Test
    void anErrorAfterTheCompletionIsIgnored() {
        final HttpResponse.BodySubscriber<BoundedBody.Body> s = subscriber(10);
        s.onSubscribe(new Subscription());
        s.onNext(chunk("ok"));
        s.onComplete();
        s.onError(new java.io.IOException("too late"));
        s.onComplete();
        assertEquals(2, s.getBody().toCompletableFuture().join().bytes().length);
    }

    @Test
    void aDeclaredLengthOverTheLimitIsRefusedWithoutReadingAnything() {
        final HttpResponse.BodySubscriber<BoundedBody.Body> declared = BoundedBody.handler(10).apply(info(200, Map.of("Content-Length", List.of("11"))));
        declared.onSubscribe(new Subscription());
        declared.onComplete();
        assertTrue(declared.getBody().toCompletableFuture().join().tooLarge());
        final HttpResponse.BodySubscriber<BoundedBody.Body> error = BoundedBody.handler(1_000_000).apply(info(500, Map.of("Content-Length", List.of(Long.toString(BoundedBody.ERROR_LIMIT + 1)))));
        error.onSubscribe(new Subscription());
        error.onComplete();
        assertTrue(error.getBody().toCompletableFuture().join().tooLarge(), "an error answer has its own, smaller limit");
        final HttpResponse.BodySubscriber<BoundedBody.Body> fits = BoundedBody.handler(10).apply(info(200, Map.of("Content-Length", List.of("10"))));
        fits.onSubscribe(new Subscription());
        fits.onComplete();
        assertFalse(fits.getBody().toCompletableFuture().join().tooLarge());
    }

    @Test
    void bodiesCompareByContent() {
        final BoundedBody.Body a = new BoundedBody.Body(new byte[]{1}, false);
        assertEquals(a, a);
        assertNotEquals(a, null);
        assertNotEquals(a, "text");
        assertNotEquals(a, new BoundedBody.Body(new byte[]{1}, true));
        assertEquals(URI.create("http://x").hashCode(), URI.create("http://x").hashCode());
    }
}
