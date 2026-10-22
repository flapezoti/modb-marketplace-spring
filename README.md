# vmodb-marketplace

Two independent implementations of the same two services from the Online Marketplace benchmark —
**Product** and **Cart**, specifically Cart's `product_replicas` table, a read replica of
Product's catalog data — sharing one set of hexagonal ports between them:

- **VMODB-backed** (`product`, `cart`): Cart stays up to date by consuming Product's outbound
  events (`PRODUCT_UPDATED`, `PRICE_UPDATED`) in transaction-id order, enforced by VMODB's
  coordinator. No ordering key, polling, or client-side merge logic needed.
- **Kafka/Redis-backed** (`product-redis`, `cart-redis`): the same two transactions, over a
  Kafka topic and a Redis-backed store, with Kafka producer sends deliberately left unkeyed — so
  nothing structurally prevents an out-of-order delivery the way VMODB's coordinator does.

**Both sides depend on the same `ports` module** — `PriceUpdatePolicy` (the price-update version
check) and `ProductCatalogPort`/`ProductReplicaCatalogPort` (storage, as a plain
`ProductSnapshot` record) — with zero VMODB, Kafka, Redis, or Spring types anywhere in that
module. `VmodbProductCatalogAdapter`/`RedisProductCatalogAdapter` (and their Cart-side
counterparts) are the only places each backend's actual technology is touched. Swapping backends
means choosing which pair of modules you run; the business rule and the storage contract are the
same classes either way — `VersionMatchPriceUpdatePolicy` is not duplicated, it's the literal
same `.class` file on both sides.

`product`/`cart` are Spring Boot applications configured through `application-*.yml` and started
with [`vmodb-spring-starter`](../vmodb-spring-starter), using its **deep DI path**:
`ProductService`/`CartService` are constructed by Spring itself (plain constructor injection),
not by VMODB's own reflection, via `vmodb-fork`'s `VmsApplication.prepare(...)` /
`VmsPreparedApplication#complete(...)` split. Neither service class carries any Spring
annotation — only each module's `Main` knows Spring is involved. `product-redis`/`cart-redis`
are ordinary Spring Boot applications with no VMODB involvement at all — confirmed by an empty
`mvn dependency:tree` for any VMODB artifact.

VMODB's coordinator and Kafka/Redis are both treated the same way: external dependencies started
independently of the services that use them, not embedded in them. See `docker-compose.yml`
(Kafka + Redis) and `start-coordinator.sh` (VMODB's coordinator, as its own standalone process —
built by `coordinator-launcher`, which depends on nothing but VMODB's `coordinator` artifact).

## Modules

- `ports` — zero dependencies, on purpose. `PriceUpdatePolicy`/`VersionMatchPriceUpdatePolicy`
  (the price-update version-check business rule) and `ProductCatalogPort`/
  `ProductReplicaCatalogPort`/`ProductSnapshot` (storage, as a plain record). Nothing here
  imports VMODB, Spring, Kafka, or Redis — confirmed by grep and by an empty
  `mvn dependency:tree`, not just by convention. Every other module below depends on this one;
  it depends on nothing.
- `common` — VMODB-specific: `Constants` (queue/table names) and the `@Event`-annotated
  DTOs (`UpdateProduct`/`ProductUpdated`, `PriceUpdate`/`PriceUpdated`) VMODB's own dispatch
  uses. Depends on `modb-api`. Used only by `product`/`cart`, deliberately not by
  `product-redis`/`cart-redis` (which have their own, independently-written event DTOs, to
  avoid pulling in a VMODB artifact just for a message shape).
- `product` — the VMODB-backed implementation. Owns the `products` table. Two transactions:
  `updateProduct` (create or update, emits `PRODUCT_UPDATED`) and `updateProductPrice` (always
  emits `PRICE_UPDATED`, but applies the price locally only if `priceUpdate.version` matches the
  product's current version — that rule lives in `ports`' `PriceUpdatePolicy`, not in
  `ProductService`). Storage access goes through `ProductCatalogPort`, implemented by
  `VmodbProductCatalogAdapter` — the only class here that touches `IProductRepository`/`Product`
  directly. `ProductService` is genuinely constructed by Spring via the deep-DI chain
  (`preparedVms` → `productRepository` → `productService` → `vmsApplication`) in `Main`, which
  also backs `ProductQueryController` (`GET /products/{sellerId}/{productId}`,
  `GET /system/info`), a read API over Spring MVC, separate from VMODB's own raw HTTP handler.
- `cart` — the VMODB-backed implementation of Cart. Owns `product_replicas`. Applies the same
  `PriceUpdatePolicy` instance Product's own `ProductService` uses, so a stale or conflicting
  price update is rejected identically on both sides. Storage access goes through
  `ProductReplicaCatalogPort`, implemented by `VmodbProductReplicaCatalogAdapter`. Same
  bean/controller pattern as `product`: `CartService` (Spring-constructed) backs
  `ReplicaQueryController` (`GET /replicas/{sellerId}/{productId}`, `GET /system/info`).
- `product-redis` — the Kafka/Redis-backed implementation of Product. `ProductController`
  (`@RestController`: `POST /products`, `PATCH /products/{sellerId}/{productId}/price`,
  `GET /products/{sellerId}/{productId}`) is the trigger, reading/writing through the same
  `ProductCatalogPort`/`PriceUpdatePolicy` as `product`'s `ProductService`, then explicitly
  publishing to Kafka (`KafkaTemplate.send(...)`, deliberately without a partition key).
  `RedisProductCatalogAdapter` stores a `ProductSnapshot` as JSON directly — no separate
  Redis-specific entity class, since the snapshot is already a plain, serializable shape.
- `cart-redis` — the Kafka/Redis-backed implementation of Cart. `ProductEventListener`
  (`@KafkaListener` on `product-updated`/`price-updated`) is the trigger, through the same
  `ProductReplicaCatalogPort`/`PriceUpdatePolicy` as `cart`'s `CartService`.
  `RedisProductReplicaCatalogAdapter` mirrors `product-redis`'s adapter. `CartController` is a
  read-only endpoint over the raw Redis-backed store, for querying the replica directly.
- `coordinator-launcher` — a standalone, long-running process for VMODB's coordinator
  (`CoordinatorMain`), the external dependency `product`/`cart` connect to — the same role
  Kafka/Redis play for `product-redis`/`cart-redis`. Depends on nothing but
  `dk.ku.di.dms.vms:coordinator`; builds the same `update_product`/`update_price` DAG `test`'s
  `CoordinatorTestSupport` builds inline, but runs it as its own process instead of a test
  tearing it down afterward.
- `test`:
  - `CartProductPriceOrderingTest` — boots both VMSes and an embedded `Coordinator` with two
    two-node DAGs (`update_product`, `update_price`; Product to Cart), then checks that
    (1) creating a product also creates Cart's replica, (2) a matching-version price update is
    applied on both sides, and (3) a conflicting-version price update is emitted by Product but
    rejected on both sides. Verifies state in-process, through each `Main`'s exposed beans.
  - `SpringQueryApiTest` — the same three-step scenario, verified instead through the
    `ProductQueryController`/`ReplicaQueryController` HTTP endpoints, while the coordinator
    session is active.
  - `CoordinatorTestSupport` — the coordinator/DAG bootstrap shared by both test classes, using
    an *embedded* coordinator (same JVM, torn down after the test) — `coordinator-launcher` is
    the standalone equivalent of this same setup, for running the VMODB side for real.

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

# then, here -- builds all 7 modules (ports, common, product, cart, product-redis, cart-redis,
# coordinator-launcher) and runs the VMODB-side test suite
JAVA_HOME=<jdk21> mvn clean install
```

Requires JDK 21 (VMODB compiles with `--enable-preview` for memory-segment APIs).

### Running the VMODB side for real (not just the test)

```
./start-coordinator.sh                                                        # terminal 1
mvn org.springframework.boot:spring-boot-maven-plugin:3.5.16:run -pl product   # terminal 2
mvn org.springframework.boot:spring-boot-maven-plugin:3.5.16:run -pl cart      # terminal 3
```
The coordinator reaches out to `product`/`cart`, retrying with backoff, so the start order
between it and the two services doesn't actually matter.

### Running the Kafka/Redis side

```
docker-compose up                                                                  # terminal 1
mvn org.springframework.boot:spring-boot-maven-plugin:3.5.16:run -pl product-redis # terminal 2
mvn org.springframework.boot:spring-boot-maven-plugin:3.5.16:run -pl cart-redis    # terminal 3
```

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
  VMODB design fact, reachable by any adopter attempting this same decoupling — see
  `vmodb-fork/README.md`'s "Fork-specific changes" section for the full writeup, including the related
  `EmbedMetadataLoader` fix this *did* require. Fixed here without any fork change: each
  service's constructor still takes the raw VMODB repository type and wraps it into the adapter
  immediately, never touching it again.
- **`ProductService`'s constructor demonstrates that deep DI supports more than repositories.**
  `ProductService(IProductRepository, PriceUpdatePolicy)`'s second parameter is a genuine,
  unrelated Spring bean (`PriceUpdatePolicy`, declared as `@Bean priceUpdatePolicy()` in
  `Main`) — not a repository, and not anything VMODB's own reflection could ever have supplied
  on its own.
- **The root `pom.xml` originally declared `modb-api`/`sdk-embed` in a plain `<dependencies>`
  block**, which Maven inherits into every child module automatically — including, if left
  unfixed, a brand-new zero-dependency module like `ports`, silently defeating the point of it.
  Moved to `<dependencyManagement>`: `common`, `product`, and `cart` now declare those
  dependencies explicitly in their own poms, instead of getting them for free by being in this
  reactor. `ports`/`product-redis`/`cart-redis` declare neither, and an empty
  `mvn dependency:tree` for VMODB artifacts in either confirms it.
- **`coordinator-launcher` depends on nothing from this repo except VMODB's own `coordinator`
  artifact** — not `product`, not `cart`, not `common`, not `ports`. Its DAG
  (`TransactionBootstrap.name(...).input(...).terminal(...)`) is built entirely from strings
  (VMS names, queue names), the same way the coordinator itself has zero compile-time coupling
  to application code. Verified by running it standalone with no `product`/`cart` present: it
  starts, listens on its configured port, and retries connecting with backoff — no crash, no
  dependency on anything that isn't there yet.
