package com.bmp.catalog.application.command;

import com.bmp.catalog.application.query.CategoryView;
import com.bmp.catalog.domain.error.CatalogErrorCode;
import com.bmp.catalog.domain.model.CatalogStatus;
import com.bmp.catalog.domain.model.Category;
import com.bmp.catalog.domain.port.CatalogRevisions;
import com.bmp.catalog.domain.port.CategoryRepository;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.id.IdGenerator;
import com.bmp.web.etag.IfMatch;
import io.micrometer.observation.annotation.Observed;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates and changes categories: names unique per level, at most five levels, no cycles. */
@Service
public class CategoryHandlers {

    private final CategoryRepository categories;
    private final CatalogLoader loader;
    private final IdGenerator ids;
    private final CatalogRevisions revisions;

    public CategoryHandlers(
            CategoryRepository categories, CatalogLoader loader, IdGenerator ids, CatalogRevisions revisions) {
        this.categories = categories;
        this.loader = loader;
        this.ids = ids;
        this.revisions = revisions;
    }

    /** PATCH semantics: {@code null} fields stay; {@code moveToRoot} moves the category to the top level. */
    public record UpdateCategory(
            UUID categoryId,
            long expectedVersion,
            @Nullable String name,
            @Nullable UUID parentId,
            boolean moveToRoot,
            @Nullable CatalogStatus status) {}

    @Transactional
    @Observed(name = "bmp.catalog.create-category")
    public CategoryView create(String name, @Nullable UUID parentId) {
        Category category = Category.create(ids.newUuid(), TenantContext.require(), name, parent(parentId));
        return CategoryView.of(categories.saveAndFlush(category));
    }

    @Transactional
    @Observed(name = "bmp.catalog.update-category")
    public CategoryView update(UpdateCategory command) {
        if (command.parentId() != null || command.moveToRoot()) {
            // The tenant's catalog revision row lock serialises moves, so no two moves check cycles on stale paths.
            revisions.next();
        }
        Category category = loader.category(command.categoryId());
        IfMatch.check(command.expectedVersion(), category.version());
        if (command.name() != null) {
            category.rename(command.name());
        }
        if (command.parentId() != null || command.moveToRoot()) {
            String oldPath = category.moveTo(parent(command.parentId()), categories.subtreeHeight(category.path()));
            Category saved = categories.saveAndFlush(category);
            categories.rebaseDescendants(oldPath, saved.path());
        }
        if (command.status() == CatalogStatus.INACTIVE) {
            category.deactivate();
        } else if (command.status() == CatalogStatus.ACTIVE) {
            category.activate();
        }
        return CategoryView.of(categories.saveAndFlush(category));
    }

    private @Nullable Category parent(@Nullable UUID parentId) {
        if (parentId == null) {
            return null;
        }
        return categories
                .findById(parentId)
                .orElseThrow(() ->
                        new BusinessRuleViolation(CatalogErrorCode.UNKNOWN_CATEGORY, Map.of("categoryId", parentId)));
    }
}
