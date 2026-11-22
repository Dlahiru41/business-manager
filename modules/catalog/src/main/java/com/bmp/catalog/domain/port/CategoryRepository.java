package com.bmp.catalog.domain.port;

import com.bmp.catalog.domain.model.Category;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Categories of the current tenant. */
public interface CategoryRepository {

    Optional<Category> findById(UUID id);

    Category save(Category category);

    /** Saves and flushes, so the returned aggregate carries its new version. */
    Category saveAndFlush(Category category);

    /** A keyset page ordered by name then id, optionally only the children of one category. */
    List<Category> findPage(@Nullable UUID parentId, @Nullable PageKey after, int limit);

    /** Levels of descendants below the category with this path (0 without children). */
    int subtreeHeight(String path);

    /** Rewrites the paths of the descendants of a moved category. */
    void rebaseDescendants(String oldPath, String newPath);
}
