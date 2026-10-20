package io.gluonify.plasma.crystallon.client;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Bounded in-memory response body: a response that is too large is never kept whole (neither announced by {@code Content-Length}, nor
 * sent in chunks); reading stops and the connection is cut. The request timeout covers the complete reading of the body.
 */
final class BoundedBody {
    /** An error body larger than this is ignored (it is not an {@code ErrorBody}). */
    static final long ERROR_LIMIT = 64L * 1024;

    private static final byte[] EMPTY = new byte[0];

    /** @param bytes the body (empty if {@code tooLarge}) @param tooLarge the body exceeded the limit */
    record Body(byte[] bytes, boolean tooLarge) {

        @Override
        public boolean equals(final Object other) {

            return other instanceof final Body body && tooLarge == body.tooLarge && Arrays.equals(bytes, body.bytes);
        }

        @Override
        public int hashCode() {

            return 31 * Arrays.hashCode(bytes) + Boolean.hashCode(tooLarge);
        }

        @Override
        public String toString() {

            return "Body[bytes=" + bytes.length + " bytes, tooLarge=" + tooLarge + "]";
        }
    }

    private BoundedBody() {
    }

    static HttpResponse.BodyHandler<Body> handler(final long pdfLimit) {
        return info -> {
            final long limit = info.statusCode() / 100 == 2 ? pdfLimit : ERROR_LIMIT;
            if (info.headers().firstValueAsLong("Content-Length").orElse(-1) > limit) {
                return HttpResponse.BodySubscribers.replacing(new Body(EMPTY, true));
            }
            return new Limited(limit);
        };
    }

    private static final class Limited implements HttpResponse.BodySubscriber<Body> {
        private final long limit;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final CompletableFuture<Body> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private boolean done;

        Limited(final long limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<Body> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(final Flow.Subscription subscription) {
            if (this.subscription != null) {
                subscription.cancel();
                return;
            }
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(final List<ByteBuffer> items) {
            if (done) {
                return;
            }
            for (final ByteBuffer item : items) {
                if (buffer.size() + (long) item.remaining() > limit) {
                    done = true;
                    subscription.cancel();
                    result.complete(new Body(EMPTY, true));
                    return;
                }
                final byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                buffer.write(chunk, 0, chunk.length);
            }
        }

        @Override
        public void onError(final Throwable error) {
            if (!done) {
                done = true;
                result.completeExceptionally(error);
            }
        }

        @Override
        public void onComplete() {
            if (!done) {
                done = true;
                result.complete(new Body(buffer.toByteArray(), false));
            }
        }
    }
}
