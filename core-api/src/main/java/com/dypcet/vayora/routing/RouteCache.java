package com.dypcet.vayora.routing;

import java.util.Optional;

/** Remembers answers from the routing service. Losing an entry only costs a lookup. */
public interface RouteCache {

    Optional<String> get(String key);

    void put(String key, String value);
}
