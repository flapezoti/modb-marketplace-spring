# vmodb-marketplace

A deliberately small, plain-vMODB implementation of a two-service subset of the
["Online Marketplace"](../../papers) benchmark: **Product** and **Cart**, specifically
Cart's `product_replicas` read replica of Product's catalog data.

This is step two of the parent Master's project's VMODB+Spring integration plan (see the
parent `CLAUDE.md`): a warm-up on vMODB's own programming model was done first in the sibling
repo `vmodb-pingpong`; this repo exercises an actual slice of the real benchmark, in isolation
from Spring, before any Spring-flavored adaptation work starts on `vmodb-fork`.

## Why Product + Cart

Cart keeps a local read replica of product price and name, kept fresh purely by consuming
Product's own outbound events (`PRODUCT_UPDATED`, `PRICE_UPDATED`) — no polling, no client-side
merge logic. This is the smallest real two-VMS pair in the upstream reference implementation
(`sources/modb/marketplace/product` and `.../cart`'s `ProductReplica`), and it maps directly onto
a documented, sourced correctness gap in the sibling repo `work/OnlineMarketLibrary_Spring`:
that repo's `CLAUDE.md` review found **§4 S#5 (ordered product updates) is not met** there,
because no Kafka producer anywhere sets a partition key. vMODB's coordinator delivers events to
a VMS in strict TID order by construction — this repo's test demonstrates exactly that property,
plus the version-guard that makes it actually matter.

## What's here

- `common` — shared `Constants`, event/input DTOs (`UpdateProduct`/`ProductUpdated`,
  `PriceUpdate`/`PriceUpdated`), trimmed to the fields this subset actually uses.
- `product` — owns the `products` table. Two transactions: `updateProduct` (create-or-update,
  emits `PRODUCT_UPDATED`) and `updateProductPrice` (emits `PRICE_UPDATED` always, but only
  applies the price locally if `priceUpdate.version` matches the product's current version).
- `cart` — owns `product_replicas` only (no `CartItem`/checkout — out of scope for this subset,
  see the parent project's AskUserQuestion decision log). Mirrors Product's version guard on
  `updateProductPrice`, so a stale/out-of-order price update is rejected identically on both
  sides.
- `test` — `CartProductPriceOrderingTest`: boots both VMSes plus an embedded `Coordinator`
  wiring two 2-node DAGs (`update_product`, `update_price`; Product → Cart), then proves, in
  three steps: (1) creating a product also creates Cart's replica, (2) a matching-version price
  update is applied on both sides, (3) a conflicting-version price update is emitted by Product
  but rejected on **both** sides — proving the version guard, not just delivery order, is what
  the replica's correctness actually depends on.

## Depends on `vmodb-fork`, not `sources/modb`

This repo's poms declare dependencies on `dk.ku.di.dms.vms:{modb-api,sdk-embed,coordinator,...}`
resolved from the local Maven repository. Build the sibling `vmodb-fork` repo first:

```
cd ../vmodb-fork
JAVA_HOME=<jdk21> mvn -pl modb-api,modb-common,sdk-embed,sdk-core,web_common,coordinator -am \
    clean install -DskipTests=true
```

Then, from this repo:

```
JAVA_HOME=<jdk21> mvn clean test
```

Requires JDK 21 specifically (vMODB compiles with `--enable-preview` for memory-segment APIs);
on this machine that's `/opt/homebrew/opt/openjdk@21`, not the default `java` on `PATH`.

## A framework constraint found while building this

**Querying a VMS over plain HTTP while it has an active coordinator session returns an empty
reply** (`curl` and Java's `HttpClient` both confirmed this — a TCP connection is accepted, but
the server never writes a response). This works perfectly *before* a coordinator connects (all
of the upstream `marketplace/test` suite's HTTP seeding calls happen exactly then, which is why
this was never hit upstream) but breaks once the VMS is engaged in coordinator-driven
transactions. Root cause not isolated further (would require digging into
`ModbHttpServer`/`VmsEventHandler`'s async channel handling) — logged here rather than chased,
since it wasn't the point of this exercise. Worked around by exposing each VMS's
`ITransactionManager`, repository, and `VmsApplication` (for `lastTidFinished()`) as public
static fields on `Main`, and reading state directly in-process instead of over HTTP. See
`Main.java` in both `product` and `cart`, and `CartProductPriceOrderingTest`'s `getProduct()` /
`getReplica()` helpers.

Relatedly: reading with `beginTransaction(0, 0, 0, true)` after real coordinator transactions
have already advanced the tid counter opens a **pre-commit snapshot** (returns `null`/stale
data) — use `VMS.lastTidFinished()` for both the `tid` and `lastTid` arguments instead, matching
the pattern the upstream `ProductHttpHandler.patch("/product/cleanup")` endpoint already used
for the same reason.

This is a *new* finding, distinct from the six framework constraints already catalogued from
the `vmodb-pingpong` exercise (DAG can't repeat a VMS, one `@Event` class per queue per VMS, no
bare-`String`-PK lookups, 32-char string truncation, hash-only key identity, no restart
recovery).
