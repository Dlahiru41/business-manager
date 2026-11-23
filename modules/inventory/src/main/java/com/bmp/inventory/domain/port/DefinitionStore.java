package com.bmp.inventory.domain.port;

import com.bmp.inventory.domain.model.DefinitionRef;
import java.util.Optional;
import java.util.UUID;

/** The replica of published transformation definitions, one row per published version. */
public interface DefinitionStore {

    void upsert(DefinitionRef definition);

    void retire(UUID definitionId, int defVersion);

    /** The newest published version of a definition. */
    Optional<DefinitionRef> latest(UUID definitionId);
}
