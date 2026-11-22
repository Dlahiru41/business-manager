package com.bmp.catalog.domain.model;

import static com.bmp.catalog.domain.CatalogFixtures.TENANT;
import static com.bmp.catalog.domain.CatalogFixtures.assertCode;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.catalog.domain.error.CatalogErrorCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CategoryTest {

    private static Category category(String name, Category parent) {
        return Category.create(UUID.randomUUID(), TENANT, name, parent);
    }

    @Test
    void shouldKeepThePathFromTheRoot() {
        Category food = Category.create(UUID.randomUUID(), TENANT, "Food", null);
        Category dry = category("Dry goods", food);
        Category fish = category("Dried fish", dry);

        assertThat(food.depth()).isEqualTo(1);
        assertThat(food.ancestorIds()).isEmpty();
        assertThat(fish.ancestorIds()).containsExactly(food.id(), dry.id());
        assertThat(fish.parentId()).isEqualTo(dry.id());
        assertThat(fish.path()).isEqualTo(food.id() + "/" + dry.id() + "/" + fish.id());
    }

    @Test
    void shouldNestAtMostFiveLevels() {
        Category level = Category.create(UUID.randomUUID(), TENANT, "1", null);
        for (int depth = 2; depth <= Category.MAX_DEPTH; depth++) {
            level = category(String.valueOf(depth), level);
        }
        Category deepest = level;

        assertCode(() -> category("6", deepest), CatalogErrorCode.CATEGORY_TOO_DEEP);
    }

    @Test
    void shouldMoveWithoutCyclesAndWithinTheDepthLimit() {
        Category food = Category.create(UUID.randomUUID(), TENANT, "Food", null);
        Category dry = category("Dry goods", food);
        Category other = Category.create(UUID.randomUUID(), TENANT, "Other", null);

        String oldPath = dry.moveTo(other, 0);

        assertThat(oldPath).isEqualTo(food.id() + "/" + dry.id());
        assertThat(dry.ancestorIds()).containsExactly(other.id());
        assertCode(() -> other.moveTo(dry, 1), CatalogErrorCode.CATEGORY_CYCLE);
        assertCode(() -> food.moveTo(dry, 4), CatalogErrorCode.CATEGORY_TOO_DEEP);
        dry.moveTo(null, 0);
        assertThat(dry.parentId()).isNull();
        assertThat(dry.depth()).isEqualTo(1);
    }

    @Test
    void shouldRenameAndDeactivate() {
        Category food = Category.create(UUID.randomUUID(), TENANT, "Food", null);

        food.rename("Groceries");
        food.deactivate();
        assertThat(food.name()).isEqualTo("Groceries");
        assertThat(food.status()).isEqualTo(CatalogStatus.INACTIVE);
        food.activate();
        assertThat(food.status()).isEqualTo(CatalogStatus.ACTIVE);
    }
}
