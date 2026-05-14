package net.minestom.web.internal.http.routes;

import io.javalin.http.Context;
import net.minestom.web.internal.scope.DashboardScope;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/// Stateful per-server scope registry + replay lifecycle: resolves the [DashboardScope] for each
/// request from the `X-Replay-Id` header / `?replay=` query, tracks the default (live) scope id,
/// and owns the replay create/remove callbacks. The stateless response/lookup helpers live in
/// [RouteResponses].
public final class ScopeRouter {
    private final ConcurrentHashMap<String, DashboardScope> scopes;
    private volatile @Nullable String defaultScopeId;
    private volatile @Nullable ScopeCreator scopeCreator;
    private volatile @Nullable Consumer<String> scopeRemover;

    public ScopeRouter(ConcurrentHashMap<String, DashboardScope> scopes) {
        this.scopes = scopes;
    }

    public void setDefaultScopeId(@Nullable String id) {
        this.defaultScopeId = id;
    }

    public @Nullable String defaultScopeId() {
        return defaultScopeId;
    }

    public void setReplayLifecycle(ScopeCreator creator, Consumer<String> remover) {
        this.scopeCreator = creator;
        this.scopeRemover = remover;
    }

    public Collection<DashboardScope> scopes() {
        return scopes.values();
    }

    DashboardScope createReplayScope(Context ctx) throws Exception {
        return scopeCreator.create(ctx);
    }

    void removeScope(String id) {
        scopeRemover.accept(id);
    }

    boolean scopeExists(String id) {
        return scopes.containsKey(id);
    }

    /// Before-middleware that resolves scope from `X-Replay-Id` header or `?replay=` query
    /// and stores it in context attribute. Call this in `before("/api/*", ...)`.
    public void resolveScopeMiddleware(Context ctx) {
        String id = RouteResponses.queryOrHeader(ctx, "replay", "X-Replay-Id");
        DashboardScope scope = scopeOrDefault(id);
        if (scope != null) ctx.attribute(RouteResponses.SCOPE_ATTR, scope);
    }

    private @Nullable DashboardScope scopeOrDefault(@Nullable String explicitId) {
        if (explicitId == null || explicitId.isEmpty()) {
            final String def = defaultScopeId;
            return def == null ? null : scopes.get(def);
        }
        DashboardScope s = scopes.get(explicitId);
        if (s != null) s.touch();
        return s;
    }

    @FunctionalInterface
    public interface ScopeCreator {
        DashboardScope create(Context ctx) throws Exception;
    }
}
