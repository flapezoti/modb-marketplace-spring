# vmodb-marketplace

A small VMODB implementation of two services from the Online Marketplace benchmark: **Product**
and **Cart**, specifically Cart's `product_replicas` table, a read replica of Product's catalog
data. Cart stays up to date only by consuming Product's outbound events (`PRODUCT_UPDATED`,
`PRICE_UPDATED`), so the example shows how VMODB's coordinator keeps a replica consistent: it
delivers events to a VMS in transaction-id order, and no ordering key, polling or client-side
merge logic is needed.

Both VMSes are Spring Boot applications configured through `application-*.yml` and started with
[`vmodb-spring-starter`](../vmodb-spring-starter), using its **deep DI path**: `ProductService`
and `CartService` are constructed by Spring itself (plain constructor injection), not by VMODB's
own reflection, via `vmodb-fork`'s `VmsApplication.prepare(...)` / `VmsPreparedApplication
#complete(...)` split. Neither service class carries any Spring annotation — only each module's
`Main` knows Spring is involved.

**`ProductService`/`CartService` are also structured as hexagonal ports and adapters**, aimed at
a different goal than deep DI: making it possible to swap VMODB for another backend without
rewriting the business rules. Each service's actual logic depends only on plain,
framework-free interfaces (`common/.../policy/PriceUpdatePolicy`,
`common/.../catalog/ProductCatalogPort`/`ProductReplicaCatalogPort`) — VMODB's repository type
and annotated entity classes are confined to one adapter each
(`VmodbProductCatalogAdapter`/`VmodbProductReplicaCatalogAdapter`) and to one line of each
service's constructor (see the Notes section for why that one line can't be removed). No second
adapter exists yet for either port — a Kafka/Redis implementation is deliberately deferred as
future validation of whether the ports are genuinely portable, not assumed.

## Modules

- `common` — shared `Constants`, the event/input classes (`UpdateProduct`/`ProductUpdated`,
  `PriceUpdate`/`PriceUpdated`), and the hexagonal ports both services depend on:
  `policy/PriceUpdatePolicy` (the version-check business rule) and
  `catalog/ProductCatalogPort`/`ProductReplicaCatalogPort`/`ProductSnapshot` (storage, as a
  plain, framework-free shape). None of these three port-related files import anything from
  VMODB or Spring — confirmed by grep, not just by convention.
- `product` — owns the `products` table. Two transactions: `updateProduct` (create or update,
  emits `PRODUCT_UPDATED`) and `updateProductPrice` (always emits `PRICE_UPDATED`, but applies the
  price locally only if `priceUpdate.version` matches the product's current version — that rule
  itself lives in the shared `PriceUpdatePolicy` port, not in `ProductService`). Storage access
  goes through `ProductCatalogPort`, implemented by `VmodbProductCatalogAdapter`.
  `IProductRepository` and `ProductService` are exposed as typed Spring beans in `Main` —
  `ProductService` genuinely constructed by Spring via the deep-DI chain (`preparedVms` →
  `productRepository` → `productService` → `vmsApplication`) — backing `ProductQueryController`
  (`GET /products/{sellerId}/{productId}`, `GET /system/info`), a read API over Spring MVC,
  separate from VMODB's own raw HTTP handler.
- `cart` — owns `product_replicas`. Applies the same `PriceUpdatePolicy` rule to `PRICE_UPDATED`
  (the identical port instance Product's own `ProductService` uses), so a stale or conflicting
  price update is rejected identically on both sides. Storage access goes through
  `ProductReplicaCatalogPort`, implemented by `VmodbProductReplicaCatalogAdapter`. Same
  bean/controller pattern as `product`: `IProductReplicaRepository`/`CartService` beans
  (`CartService` likewise Spring-constructed) back `ReplicaQueryController`
  (`GET /replicas/{sellerId}/{productId}`, `GET /system/info`).
- `test`:
  - `CartProductPriceOrderingTest` — boots both VMSes and an embedded `Coordinator` with two
    two-node DAGs (`update_product`, `update_price`; Product to Cart), then checks that
    (1) creating a product also creates Cart's replica, (2) a matching-version price update is
    applied on both sides, and (3) a conflicting-version price update is emitted by Product but
    rejected on both sides. Verifies state in-process, through each `Main`'s exposed beans.
  - `SpringQueryApiTest` — the same three-step scenario, verified instead through the
    `ProductQueryController`/`ReplicaQueryController` HTTP endpoints, while the coordinator
    session is active.
  - `CoordinatorTestSupport` — the coordinator/DAG bootstrap shared by both test classes.

## Building and testing

The VMODB modules (`modb-api`, `modb-common`, `sdk-embed`, `sdk-core`, `web_common`,
`coordinator`, version `1.0-SNAPSHOT`) and `vmodb-spring-starter` must be installed in the local
Maven repository first, for example:

```
# in the VMODB source tree
JAVA_HOME=<jdk21> mvn -pl modb-api,modb-common,sdk-embed,sdk-core,web_common,coordinator -am \
    clean install -DskipTests=true

# in vmodb-spring-starter
JAVA_HOME=<jdk21> mvn clean install

# then, here
JAVA_HOME=<jdk21> mvn clean test
```

Requires JDK 21 (VMODB compiles with `--enable-preview` for memory-segment APIs).

## Notes

- **Querying a VMS over plain HTTP while a coordinator session is active returns an empty
  reply** (checked with `curl` and Java's `HttpClient`) — but only through VMODB's own raw HTTP
  handler (`ProductHttpHandler`/`CartHttpHandler` in each `Main`). The Spring MVC endpoints
  (`ProductQueryController`/`ReplicaQueryController`, on their own `server.port`) do not go
  through that handler and are unaffected — `SpringQueryApiTest` exercises them specifically
  while the coordinator session is live. `CartProductPriceOrderingTest` still verifies state
  in-process, through the beans and repositories exposed as static fields on each `Main`.
- **`VmsApplication.getService(name)` is keyed by canonical class name, not the
  `@Microservice("...")` annotation value.** `VmsMetadataLoader.loadMicroserviceClasses()` does
  `loadedMicroserviceInstances.put(clazz.getCanonicalName(), vmsInstance)`. This matters here
  even though `ProductService`/`CartService` are now Spring-constructed (deep DI, not looked up
  via `getService(...)`): the same `Class#getName()` convention is what the `vmsInstances` map
  passed to `VmsPreparedApplication#complete(...)` must be keyed by, for VMODB's own internal
  bookkeeping to find them afterward (e.g. anything still reading `vms.getService(...)`
  elsewhere).
- **`IEntity<PK>.getId()`'s default implementation throws `UnsupportedOperationException`**, and
  neither `Product` nor `ProductReplica` overrides it. Jackson's default bean introspection
  auto-detects that public getter-shaped method as an "id" property and calls it when
  serializing to JSON, which surfaced as a 500 from `ProductQueryController`/
  `ReplicaQueryController` (`JsonMappingException: (was UnsupportedOperationException) ...
  through reference chain Product["id"]`). Both entity classes carry
  `@JsonIgnoreProperties({"id"})` for this reason.
- **VMODB's repository-proxy class generation cannot run twice in the same classloader.** Once
  a second test class (`SpringQueryApiTest`) also booted `product`/`cart`, it failed — in the
  same JVM as `CartProductPriceOrderingTest` — with `IllegalStateException: Cannot inject
  already loaded type: class ProductRepositoryImpl`. `test/pom.xml` sets Surefire's
  `<reuseForks>false</reuseForks>` so every test class that boots a VMS gets its own JVM — add
  a third such test class and it will need this too, not just a symptom to work around once.
- **Read with `VMS.lastTidFinished()`, not tid 0.** `beginTransaction(0, 0, 0, true)` after
  coordinator transactions have advanced the tid counter opens a pre-commit snapshot and returns
  `null` or stale data. Pass `lastTidFinished()` as both `tid` and `lastTid`.
- **The `VmsApplication.prepare(...)` call (formerly `build(...)`) stays in each module's
  `Main`.** VMODB keeps only the `@Microservice`/`@VmsTable` classes in the direct caller's
  package, so the call must be made from the service's own package (see `vmodb-spring-starter`).
  Each `Main` now wires four beans instead of one — `preparedVms` → the table's repository →
  the `@Microservice` service (plain `new ProductService(...)`/`new CartService(...)`, genuinely
  Spring-constructed) → `vmsApplication` (via `prepared.complete(...)`) — the deep-DI path added
  on top of `vmodb-fork`'s `VmsApplication.prepare(...)`/`VmsPreparedApplication#complete(...)`
  split (see `vmodb-fork/README.md`). `ProductQueryController`/`ReplicaQueryController` and both
  test classes needed no changes for this: they depend on `IProductRepository`/`ProductService`/
  `VmsApplication` by type only, not on how those beans get constructed.
- **Both VMSes run in one JVM in the test**, so their config files have distinct names
  (`application-product.yml`, `application-cart.yml`), selected with
  `System.setProperty("spring.config.name", ...)` in each `Main.main()`. Otherwise Spring Boot
  loads the first `application.yml` on the classpath for both.
- Other general VMODB constraints hit while building this, independent of Spring or anything in
  this repo specifically: a VMS cannot appear twice in one `TransactionDAG`; one `@Event` class
  must map to one queue name per VMS; `lookupByKey` does not work with a bare `String` primary
  key; strings in tables are truncated to 32 characters; keys are identified by `hashCode()`
  only; state did not survive a VMS restart in testing.
- **`ConfigUtils.getCallerPackage()` originally recognized only `VmsApplication.build(...)` as
  an entry point**, by hardcoding the stack-trace method name `"build"`. Adding
  `VmsApplication.prepare(...)` (a differently-named entry point, for the deep-DI path) as a
  second caller needed a one-line widening of that check in `vmodb-fork` — see
  `vmodb-fork/README.md`'s "Fork-specific changes" section. Without it, `prepare(...)` always
  threw `IllegalStateException: Cannot identify package.`, regardless of caller.
- **VMODB decides which tables need a repository proxy built by scanning the `@Microservice`
  class's own constructor — there's no other mechanism to ask for one.** Hiding
  `IProductRepository` entirely behind `VmodbProductCatalogAdapter` (so `ProductService`'s
  constructor never mentioned it) silently told VMODB this VMS needs no repositories, breaking
  the unrelated `productRepository` bean `ProductQueryController` needs too. This is a general
  VMODB design fact, not caused by anything in this project — see `vmodb-fork/README.md`'s
  "Fork-specific changes" section for the full writeup, including the related
  `EmbedMetadataLoader` fix this *did* require. Fixed here without any fork change: each
  service's constructor still takes the raw VMODB repository type and wraps it into the adapter
  immediately, never touching it again.
- **`ProductService`'s constructor demonstrates that deep DI supports more than repositories.**
  `ProductService(IProductRepository, PriceUpdatePolicy)`'s second parameter is a genuine,
  unrelated Spring bean (`PriceUpdatePolicy`, declared as `@Bean priceUpdatePolicy()` in
  `Main`) — not a repository, and not anything VMODB's own reflection could ever have supplied
  on its own.
