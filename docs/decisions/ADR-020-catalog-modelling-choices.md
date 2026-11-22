# ADR-020: Catalog modelling choices left open by the phase file

- Status: accepted
- Date: 2026-11-22
- Phase / task: 01 / T01-02..T01-04

## Context
The phase file and domain model leave several details open. Each choice below was taken as the most conservative
option that keeps quantities and costs correct and auditable (CLAUDE.md §1.3).

## Decisions
1. **Own packets of loose goods have their own stock state.** LOOSE_REPACK variants get one state `PACKED_<UNIT CODE>`
   per sell-only packaging unit, besides `DEFAULT` (loose). The REPACK definition moves `qtyInBase` grams from
   `DEFAULT` to that state, and selling a packet takes from it. Packets are thus counted apart from loose stock and
   per size, matching "stock is kept of the packaging unit itself" for PREPACKED units. Buyable units (kg bags) stay
   in `DEFAULT`.
2. **Template stock-state defaults follow the buy flag.** BULK_BREAK: buyable units are SEALED, everything else OPEN.
   LOOSE_REPACK: buyable units DEFAULT, sell-only units their own packed state. Requests can always name a state.
3. **Packaging units may be defined in another unit** (`definedAs`, the 18 kg can). Their `qtyInBase` follows through
   the unit converter and is recomputed when the product's conversions change (CAT-14). Template definitions size both
   of their lines by the packaging unit, so a resize gives a published definition a new published version (published
   versions stay immutable, CAT-10); drafts are resized and published later.
4. **`pack` has no SI factor.** A pack has no fixed size, so it converts only through packaging units and product
   conversions; a silent 1 pack = 1 piece conversion would be wrong. Same for tenant units created without a factor.
5. **Conversions chain at most one product conversion** with SI steps on either side (g → kg → ml). Longer chains are
   refused (`UNIT_NOT_CONVERTIBLE`).
6. **Category paths are text paths of ids** (`root/child/self`), not `ltree`, so no PostgreSQL extension is needed;
   renames never rewrite paths; moves rewrite descendants in one statement.
7. **Tracking flags are columns**, not a JSON document: inventory and sales filter on them.
8. **Regeneration copies the packaging units of the oldest active variant** to new combinations; without one, only the
   base unit is created. Regeneration never reactivates variants (an excluded-then-included combination is
   reactivated explicitly).
9. **Attribute types are fixed once a product has variants** (`ATTRIBUTE_TYPES_LOCKED`): existing variants would have
   no value for a new attribute. Values may be added and renamed at any time; renaming republishes the variants.
10. **Transformation definition versions are frozen** in `transformation_definition_version` (append-only) when
    published; the definition row holds the current draft or the latest published content.
11. **Additions to the endpoint table**: `reactivate` for products and variants and `retire` for definitions (CAT-11
    needs a way back and a way out), with the event `TransformationDefinitionRetired`.
12. **Tax categories are checked through an SPI** (`catalog.api.spi.TaxCategoryLookup`) that pricing implements;
    without pricing every id is accepted, since no tax category can exist.

## Consequences
Inventory (phase 02) must treat `PACKED_*` states as ordinary states and keep every published definition version it
receives. A product whose packets are bought ready-packed should mark them buyable or name their state explicitly.
