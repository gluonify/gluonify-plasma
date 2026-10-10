package io.gluonify.plasma.crystallon.client;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Corps de réponse en mémoire, borné : une réponse trop grande n'est jamais gardée en entier (ni annoncée par {@code Content-Length}, ni
 * envoyée en morceaux) ; la lecture s'arrête et la connexion est coupée. Le délai de la requête couvre la lecture complète du corps.
 */
final class BoundedBody {
    /** Un corps d'erreur plus grand que cela est ignoré (ce n'est pas un {@code ErrorBody}). */
    static final long ERROR_LIMIT = 64 * 1024;

    private static final byte[] EMPTY = new byte[0];

    /** @param bytes le corps (vide si {@code tooLarge}) @param tooLarge le corps dépassait la limite */
    record Body(byte[] bytes, boolean tooLarge) {
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
